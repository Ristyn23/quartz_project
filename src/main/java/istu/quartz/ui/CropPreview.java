package istu.quartz.ui;

import javax.swing.SwingUtilities;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Area;
import java.awt.image.BufferedImage;

/** Rectangle selection stored in source pixels, independent of display zoom and pan. */
final class CropPreview extends ImagePreview {
    private static final int LEFT = 1, RIGHT = 2, TOP = 4, BOTTOM = 8;
    private static final int HANDLE_RADIUS = 6;
    private final Runnable changed;
    private Rectangle selection;
    private Rectangle beforeDrag;
    private Point dragStart;
    private int resizeEdges;
    private boolean moving;
    private boolean selecting;
    private boolean editable = true;

    CropPreview(Runnable changed) {
        this.changed = changed;
        MouseAdapter mouse = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) {
                if (!selecting || !editable || !SwingUtilities.isLeftMouseButton(event)
                        || (event.getModifiersEx() & MouseEvent.BUTTON2_DOWN_MASK) != 0) return;
                Rectangle bounds = imageBounds();
                if (bounds.isEmpty()) return;
                resizeEdges = hitEdges(event.getPoint());
                Point point = imagePoint(event.getPoint(), resizeEdges != 0);
                if (point == null) return;
                beforeDrag = getSelection();
                dragStart = point;
                moving = resizeEdges == 0 && selection != null && selection.contains(point);
                if (!moving && resizeEdges == 0) selection = new Rectangle(point.x, point.y, 1, 1);
                selectionChanged();
            }

            @Override public void mouseDragged(MouseEvent event) {
                if (dragStart == null || !editable
                        || (event.getModifiersEx() & MouseEvent.BUTTON1_DOWN_MASK) == 0) return;
                Point point = imagePoint(event.getPoint(), true);
                if (point == null) return;
                int width = getImage().getWidth(), height = getImage().getHeight();
                if (moving) {
                    selection = new Rectangle(beforeDrag);
                    selection.x = Math.max(0, Math.min(width - selection.width, beforeDrag.x + point.x - dragStart.x));
                    selection.y = Math.max(0, Math.min(height - selection.height, beforeDrag.y + point.y - dragStart.y));
                } else if (resizeEdges != 0) {
                    Point edge = imageEdge(event.getPoint());
                    int x1 = beforeDrag.x, x2 = beforeDrag.x + beforeDrag.width;
                    int y1 = beforeDrag.y, y2 = beforeDrag.y + beforeDrag.height;
                    if ((resizeEdges & LEFT) != 0) x1 = edge.x;
                    if ((resizeEdges & RIGHT) != 0) x2 = edge.x;
                    if ((resizeEdges & TOP) != 0) y1 = edge.y;
                    if ((resizeEdges & BOTTOM) != 0) y2 = edge.y;
                    int left = Math.min(width - 1, Math.min(x1, x2));
                    int top = Math.min(height - 1, Math.min(y1, y2));
                    selection = new Rectangle(left, top, Math.min(width - left, Math.max(1, Math.abs(x2 - x1))),
                            Math.min(height - top, Math.max(1, Math.abs(y2 - y1))));
                } else {
                    selection = new Rectangle(Math.min(dragStart.x, point.x), Math.min(dragStart.y, point.y),
                            Math.abs(point.x - dragStart.x) + 1, Math.abs(point.y - dragStart.y) + 1);
                }
                selectionChanged();
            }

            @Override public void mouseReleased(MouseEvent event) {
                if (!SwingUtilities.isLeftMouseButton(event) || dragStart == null) return;
                if (!moving && resizeEdges == 0 && selection.width == 1 && selection.height == 1)
                    selection = beforeDrag;
                endDrag();
                selectionChanged();
            }

            @Override public void mouseMoved(MouseEvent event) {
                if (!selecting || !editable) return;
                int edges = hitEdges(event.getPoint());
                int cursor = Cursor.CROSSHAIR_CURSOR;
                if (edges == (LEFT | TOP) || edges == (RIGHT | BOTTOM)) cursor = Cursor.NW_RESIZE_CURSOR;
                else if (edges == (RIGHT | TOP) || edges == (LEFT | BOTTOM)) cursor = Cursor.NE_RESIZE_CURSOR;
                else if ((edges & (LEFT | RIGHT)) != 0) cursor = Cursor.E_RESIZE_CURSOR;
                else if (edges != 0) cursor = Cursor.N_RESIZE_CURSOR;
                else if (selection != null) {
                    Point point = imagePoint(event.getPoint(), false);
                    if (point != null && selection.contains(point)) cursor = Cursor.MOVE_CURSOR;
                }
                setCursor(Cursor.getPredefinedCursor(cursor));
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    Rectangle getSelection() { return selection == null ? null : new Rectangle(selection); }

    void setSelecting(boolean selecting) {
        this.selecting = selecting;
        endDrag();
        setCursor(Cursor.getPredefinedCursor(selecting && editable ? Cursor.CROSSHAIR_CURSOR : Cursor.DEFAULT_CURSOR));
        repaint();
    }

    void setEditable(boolean editable) {
        this.editable = editable;
        if (!editable) endDrag();
    }

    void clearSelection() {
        selection = null;
        endDrag();
        selectionChanged();
    }

    @Override void setImage(BufferedImage image) {
        super.setImage(image);
        clearSelection();
    }

    private void endDrag() {
        dragStart = null;
        beforeDrag = null;
        resizeEdges = 0;
        moving = false;
    }

    private void selectionChanged() { changed.run(); repaint(); }

    private Point imageEdge(Point point) {
        Rectangle bounds = imageBounds();
        return new Point(Math.max(0, Math.min(getImage().getWidth(),
                (int) Math.round((point.x - bounds.x) * (double) getImage().getWidth() / bounds.width))),
                Math.max(0, Math.min(getImage().getHeight(),
                (int) Math.round((point.y - bounds.y) * (double) getImage().getHeight() / bounds.height))));
    }

    private Rectangle screenSelection() {
        Rectangle bounds = imageBounds();
        int x1 = bounds.x + (int) Math.round(selection.x * (double) bounds.width / getImage().getWidth());
        int y1 = bounds.y + (int) Math.round(selection.y * (double) bounds.height / getImage().getHeight());
        int x2 = bounds.x + (int) Math.round((selection.x + selection.width) * (double) bounds.width / getImage().getWidth());
        int y2 = bounds.y + (int) Math.round((selection.y + selection.height) * (double) bounds.height / getImage().getHeight());
        return new Rectangle(x1, y1, x2 - x1, y2 - y1);
    }

    private int hitEdges(Point point) {
        if (selection == null || getImage() == null) return 0;
        Rectangle rect = screenSelection();
        if (!new Rectangle(rect.x - HANDLE_RADIUS, rect.y - HANDLE_RADIUS,
                rect.width + 2 * HANDLE_RADIUS + 1, rect.height + 2 * HANDLE_RADIUS + 1).contains(point)) return 0;
        int edges = 0;
        if (Math.abs(point.x - rect.x) <= HANDLE_RADIUS) edges |= LEFT;
        else if (Math.abs(point.x - rect.x - rect.width) <= HANDLE_RADIUS) edges |= RIGHT;
        if (Math.abs(point.y - rect.y) <= HANDLE_RADIUS) edges |= TOP;
        else if (Math.abs(point.y - rect.y - rect.height) <= HANDLE_RADIUS) edges |= BOTTOM;
        return edges;
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        if (selection == null || getImage() == null) return;
        Rectangle rect = screenSelection();
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            Area outside = new Area(imageBounds());
            outside.subtract(new Area(rect));
            g.setColor(new Color(0, 0, 0, 130));
            g.fill(outside);
            g.setColor(Color.BLACK);
            g.setStroke(new BasicStroke(3));
            g.drawRect(rect.x, rect.y, rect.width, rect.height);
            g.setStroke(new BasicStroke(1));
            g.setColor(Color.YELLOW);
            g.drawRect(rect.x, rect.y, rect.width, rect.height);
            if (selecting && editable) {
                for (int x : new int[]{rect.x, rect.x + rect.width / 2, rect.x + rect.width}) {
                    for (int y : new int[]{rect.y, rect.y + rect.height / 2, rect.y + rect.height}) {
                        if (x == rect.x + rect.width / 2 && y == rect.y + rect.height / 2) continue;
                        g.fillRect(x - 3, y - 3, 7, 7);
                    }
                }
            }
        } finally { g.dispose(); }
    }
}
