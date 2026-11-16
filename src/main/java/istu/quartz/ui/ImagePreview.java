package istu.quartz.ui;

import javax.swing.JPanel;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.InputEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;

class ImagePreview extends JPanel {

    private static final double ZOOM_STEP = 1.2;
    private static final double MAX_ZOOM = 16;

    private BufferedImage image;
    private BufferedImage overlay;
    private double zoom = 1;
    private double centerX = 0.5;
    private double centerY = 0.5;

    ImagePreview() {
        addMouseWheelListener(this::zoomAtCursor);
    }

    void setImage(BufferedImage image) {
        this.image = image;
        zoom = 1;
        centerX = 0.5;
        centerY = 0.5;
        setOverlay(null);
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
        double fit = Math.min((double) getWidth() / image.getWidth(), (double) getHeight() / image.getHeight());
        double scale = fit * zoom;
        int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
        return new Rectangle(imageOffset(getWidth(), width, centerX), imageOffset(getHeight(), height, centerY),
                width, height);
    }

    private int imageOffset(int viewportSize, int imageSize, double center) {
        if (imageSize <= viewportSize) {
            return (viewportSize - imageSize) / 2;
        }
        double offset = viewportSize / 2.0 - center * imageSize;
        return (int) Math.round(Math.max(viewportSize - imageSize, Math.min(0, offset)));
    }

    private void zoomAtCursor(MouseWheelEvent event) {
        Rectangle before = imageBounds();
        Point cursor = event.getPoint();
        if (!before.contains(cursor) || (event.getModifiersEx() & InputEvent.BUTTON1_DOWN_MASK) != 0) {
            return;
        }

        double nextZoom = Math.max(1, Math.min(MAX_ZOOM, zoom * Math.pow(ZOOM_STEP, -event.getPreciseWheelRotation())));
        if (nextZoom == zoom) {
            return;
        }

        zoom = nextZoom;
        if (zoom == 1) {
            centerX = 0.5;
            centerY = 0.5;
        } else {
            Rectangle after = imageBounds();
            double imageX = (cursor.x - before.x) / (double) before.width;
            double imageY = (cursor.y - before.y) / (double) before.height;
            centerX = imageX + (getWidth() / 2.0 - cursor.x) / after.width;
            centerY = imageY + (getHeight() / 2.0 - cursor.y) / after.height;
        }
        event.consume();
        repaint();
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
