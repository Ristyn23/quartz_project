package istu.quartz.ui;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.InputEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

class ImagePreview extends JPanel {

    private static final double ZOOM_STEP = 1.2;
    static final double MIN_SCALE = 0.01;
    static final double MAX_SCALE = 16;

    private BufferedImage image;
    private BufferedImage overlay;
    static final class ViewState {
        // NaN means fit-to-panel. Otherwise scale is screen pixels per image pixel.
        private double scale = Double.NaN;
        private double centerX = 0.5;
        private double centerY = 0.5;
        private final List<Runnable> listeners = new ArrayList<>();

        void addChangeListener(Runnable listener) {
            listeners.add(listener);
        }

        void reset() {
            scale = Double.NaN;
            centerX = centerY = 0.5;
            changed();
        }

        private void changed() {
            for (Runnable listener : listeners) listener.run();
        }
    }

    private final ViewState view;
    private final boolean resetOnImage;
    private Point panStart;
    private Rectangle panBounds;

    ImagePreview() {
        this(new ViewState(), true);
    }

    ImagePreview(ViewState view) {
        this(view, false);
    }

    private ImagePreview(ViewState view, boolean resetOnImage) {
        this.view = view;
        this.resetOnImage = resetOnImage;
        view.listeners.add(this::repaint);
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent event) { view.changed(); }
        });
        addMouseWheelListener(this::zoomAtCursor);
        setToolTipText("Колесо — масштаб; зажатое колёсико — перемещение изображения.");
        MouseAdapter pan = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                Rectangle bounds = imageBounds();
                if (SwingUtilities.isMiddleMouseButton(event) && bounds.contains(event.getPoint())
                        && (event.getModifiersEx() & InputEvent.BUTTON1_DOWN_MASK) == 0
                        && (bounds.width > getWidth() || bounds.height > getHeight())) {
                    panStart = event.getPoint();
                    panBounds = bounds;
                    setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                    event.consume();
                }
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                if (panStart == null || (event.getModifiersEx() & InputEvent.BUTTON2_DOWN_MASK) == 0) return;
                int x = panBounds.x + event.getX() - panStart.x;
                int y = panBounds.y + event.getY() - panStart.y;
                setCenter((getWidth() / 2.0 - x) / panBounds.width,
                        (getHeight() / 2.0 - y) / panBounds.height, panBounds.width, panBounds.height);
                view.changed();
                event.consume();
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                if (SwingUtilities.isMiddleMouseButton(event)) stopPan();
            }
        };
        addMouseListener(pan);
        addMouseMotionListener(pan);
    }

    void setImage(BufferedImage image) {
        this.image = image;
        stopPan();
        if (resetOnImage) view.reset();
        setOverlay(null);
        view.changed();
    }

    BufferedImage getImage() {
        return image;
    }

    void setOverlay(BufferedImage overlay) {
        this.overlay = overlay;
        repaint();
    }

    Rectangle imageBounds() {
        if (image == null || getWidth() == 0 || getHeight() == 0) {
            return new Rectangle();
        }
        double scale = imageScale();
        int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
        return new Rectangle((int) Math.round(getWidth() / 2.0 - view.centerX * width),
                (int) Math.round(getHeight() / 2.0 - view.centerY * height),
                width, height);
    }

    double imageScale() {
        if (image == null || getWidth() <= 0 || getHeight() <= 0) return Double.NaN;
        return Double.isNaN(view.scale)
                ? Math.min((double) getWidth() / image.getWidth(), (double) getHeight() / image.getHeight())
                : view.scale;
    }

    void zoomBy(double factor) {
        zoomTo(imageScale() * factor);
    }

    void zoomTo(double scale) {
        zoomTo(scale, new Point(getWidth() / 2, getHeight() / 2));
    }

    private void stopPan() {
        panStart = null;
        panBounds = null;
        setCursor(Cursor.getDefaultCursor());
    }

    private void setCenter(double x, double y, int width, int height) {
        double marginX = Math.min(0.5, getWidth() / (2.0 * width));
        double marginY = Math.min(0.5, getHeight() / (2.0 * height));
        view.centerX = Math.max(marginX, Math.min(1 - marginX, x));
        view.centerY = Math.max(marginY, Math.min(1 - marginY, y));
    }

    private void zoomAtCursor(MouseWheelEvent event) {
        Rectangle before = imageBounds();
        Point cursor = event.getPoint();
        if (!before.contains(cursor) || panStart != null
                || (event.getModifiersEx() & InputEvent.BUTTON1_DOWN_MASK) != 0) {
            return;
        }

        zoomTo(imageScale() * Math.pow(ZOOM_STEP, -event.getPreciseWheelRotation()), cursor);
        event.consume();
    }

    private void zoomTo(double scale, Point anchor) {
        Rectangle before = imageBounds();
        if (before.isEmpty() || !Double.isFinite(scale)) return;
        double next = Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale));
        if (next == imageScale()) return;
        double imageX = (anchor.x - before.x) / (double) before.width;
        double imageY = (anchor.y - before.y) / (double) before.height;
        view.scale = next;
        Rectangle after = imageBounds();
        setCenter(imageX + (getWidth() / 2.0 - anchor.x) / after.width,
                imageY + (getHeight() / 2.0 - anchor.y) / after.height, after.width, after.height);
        view.changed();
    }

    Point imagePoint(Point cursor, boolean clampToImage) {
        Rectangle bounds = imageBounds();
        Rectangle visible = bounds.intersection(new Rectangle(0, 0, getWidth(), getHeight()));
        if (visible.isEmpty() || (!clampToImage && !visible.contains(cursor))) {
            return null;
        }
        int x = Math.max(visible.x, Math.min(visible.x + visible.width - 1, cursor.x)) - bounds.x;
        int y = Math.max(visible.y, Math.min(visible.y + visible.height - 1, cursor.y)) - bounds.y;
        return new Point(imageCoordinate(x, bounds.width, image.getWidth(), clampToImage),
                imageCoordinate(y, bounds.height, image.getHeight(), clampToImage));
    }

    private int imageCoordinate(int pixel, int previewSize, int imageSize, boolean clampToImage) {
        if (clampToImage && pixel == 0) {
            return 0;
        }
        if (clampToImage && pixel == previewSize - 1) {
            return imageSize - 1;
        }
        return (int) ((pixel + 0.5) * imageSize / previewSize);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Rectangle bounds = imageBounds();
        if (bounds.isEmpty()) {
            return;
        }
        Graphics2D canvas = (Graphics2D) graphics.create();
        try {
            canvas.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            canvas.drawImage(image, bounds.x, bounds.y, bounds.width, bounds.height, null);
            if (overlay != null) {
                canvas.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                canvas.drawImage(overlay, bounds.x, bounds.y, bounds.width, bounds.height, null);
            }
        } finally {
            canvas.dispose();
        }
    }
}
