package istu.quartz.ui;

import javax.swing.*;
import java.awt.geom.*;
import java.util.ArrayList;
import java.util.List;
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
    private double angle, beforeAngle;
    private boolean rotating;
    private Point rotateStart;
    private boolean polygonMode, closed;
    private final List<Point> vertices = new ArrayList<>();
    private int vertex = -1;
    private List<Point> beforeVertices;

    void setPolygonMode(boolean polygonMode) {
        if (this.polygonMode != polygonMode) { clearSelection(); this.polygonMode = polygonMode; }
    }
    boolean isPolygonMode() { return polygonMode; }
    boolean hasDraft() { return polygonMode && !closed && !vertices.isEmpty(); }
    double getAngle() { return Math.toDegrees(angle); }
    void setAngle(double degrees) {
        if (polygonMode || selection == null || !Double.isFinite(degrees)) return;
        angle = Math.toRadians(Math.IEEEremainder(degrees, 360)); selectionChanged();
    }
    Rectangle getRectangle() { return polygonMode ? null : getSelection(); }
    Shape getShape() {
        if (polygonMode) return closed ? polygonPath() : null;
        if (selection == null) return null;
        return AffineTransform.getRotateInstance(angle, selection.getCenterX(), selection.getCenterY()).createTransformedShape(selection);
    }
    double selectedArea() {
        if (!polygonMode) return selection == null ? 0 : (double) selection.width * selection.height;
        return closed ? polygonArea(vertices) : 0;
    }
    private Rectangle beforeDrag;
    private Point dragStart;
    private int resizeEdges;
    private boolean moving;
    private boolean selecting;
    private boolean editable = true;

    CropPreview(Runnable changed) {
        this.changed = changed;
        setFocusable(true);
        bind("ENTER", "closePolygon", this::closePolygon);
        bind("ESCAPE", "cancelSelection", this::clearSelection);
        bind("DELETE", "deleteVertex", this::deleteVertex);
        MouseAdapter mouse = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) {
                if (!selecting || !editable || !SwingUtilities.isLeftMouseButton(event)
                        || (event.getModifiersEx() & MouseEvent.BUTTON2_DOWN_MASK) != 0) return;
                Rectangle bounds = imageBounds();
                if (bounds.isEmpty()) return;
                requestFocusInWindow();
                if (polygonMode) { polygonPressed(event); return; }
                beforeAngle = angle;
                rotating = selection != null && rotationHandle().distance(event.getPoint()) <= 9;
                rotateStart = event.getPoint();
                resizeEdges = rotating ? 0 : hitEdges(event.getPoint());
                Point point = imagePoint(event.getPoint(), rotating || resizeEdges != 0);
                if (point == null) return;
                beforeDrag = getSelection();
                dragStart = point;
                moving = !rotating && resizeEdges == 0 && selection != null && getShape().contains(point.x + .5, point.y + .5);
                if (!moving && !rotating && resizeEdges == 0) { angle = 0; selection = new Rectangle(point.x, point.y, 1, 1); }
                selectionChanged();
            }

            @Override public void mouseDragged(MouseEvent event) {
                if (dragStart == null || !editable
                        || (event.getModifiersEx() & MouseEvent.BUTTON1_DOWN_MASK) == 0) return;
                Point point = imagePoint(event.getPoint(), true);
                if (point == null) return;
                if (polygonMode) { polygonDragged(point); return; }
                if (rotating) {
                    Rectangle frame = screenSelection();
                    double cx = frame.getCenterX(), cy = frame.getCenterY();
                    angle = Math.IEEEremainder(beforeAngle + Math.atan2(event.getY() - cy, event.getX() - cx)
                            - Math.atan2(rotateStart.y - cy, rotateStart.x - cx), 2 * Math.PI);
                    selectionChanged(); return;
                }
                int width = getImage().getWidth(), height = getImage().getHeight();
                if (moving) {
                    selection = new Rectangle(beforeDrag);
                    selection.x = Math.max(0, Math.min(width - selection.width, beforeDrag.x + point.x - dragStart.x));
                    selection.y = Math.max(0, Math.min(height - selection.height, beforeDrag.y + point.y - dragStart.y));
                } else if (resizeEdges != 0) {
                    Point edge = imageEdge(event.getPoint());
                    Point2D local = AffineTransform.getRotateInstance(-angle, beforeDrag.getCenterX(), beforeDrag.getCenterY())
                            .transform(edge, null);
                    edge = new Point((int) Math.round(local.getX()), (int) Math.round(local.getY()));
                    int x1 = beforeDrag.x, x2 = beforeDrag.x + beforeDrag.width;
                    int y1 = beforeDrag.y, y2 = beforeDrag.y + beforeDrag.height;
                    if ((resizeEdges & LEFT) != 0) x1 = edge.x;
                    if ((resizeEdges & RIGHT) != 0) x2 = edge.x;
                    if ((resizeEdges & TOP) != 0) y1 = edge.y;
                    if ((resizeEdges & BOTTOM) != 0) y2 = edge.y;
                    int left = angle == 0 ? Math.min(width - 1, Math.min(x1, x2)) : Math.min(x1, x2);
                    int top = angle == 0 ? Math.min(height - 1, Math.min(y1, y2)) : Math.min(y1, y2);
                    int newWidth = Math.max(1, Math.abs(x2 - x1)), newHeight = Math.max(1, Math.abs(y2 - y1));
                    if (angle == 0) { newWidth = Math.min(width - left, newWidth); newHeight = Math.min(height - top, newHeight); }
                    Point2D center = AffineTransform.getRotateInstance(angle, beforeDrag.getCenterX(), beforeDrag.getCenterY())
                            .transform(new Point2D.Double(left + newWidth / 2.0, top + newHeight / 2.0), null);
                    selection = new Rectangle((int) Math.round(center.getX() - newWidth / 2.0),
                            (int) Math.round(center.getY() - newHeight / 2.0), newWidth, newHeight);
                } else {
                    selection = new Rectangle(Math.min(dragStart.x, point.x), Math.min(dragStart.y, point.y),
                            Math.abs(point.x - dragStart.x) + 1, Math.abs(point.y - dragStart.y) + 1);
                }
                selectionChanged();
            }

            @Override public void mouseReleased(MouseEvent event) {
                if (!SwingUtilities.isLeftMouseButton(event) || dragStart == null) return;
                if (polygonMode) { endDrag(); return; }
                if (!moving && !rotating && resizeEdges == 0 && selection.width == 1 && selection.height == 1) {
                    selection = beforeDrag; angle = beforeAngle;
                }
                endDrag();
                selectionChanged();
            }

            @Override public void mouseMoved(MouseEvent event) {
                if (!selecting || !editable) return;
                if (polygonMode) { setCursor(Cursor.getPredefinedCursor(hitVertex(event.getPoint()) >= 0 ? Cursor.HAND_CURSOR : Cursor.CROSSHAIR_CURSOR)); return; }
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

    Rectangle getSelection() { return polygonMode ? (closed ? polygonPath().getBounds() : null) : selection == null ? null : new Rectangle(selection); }

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
        selection = null; angle = 0; vertices.clear(); closed = false; vertex = -1;
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
        moving = false; rotating = false; beforeVertices = null;
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
        Point2D local = AffineTransform.getRotateInstance(-angle, rect.getCenterX(), rect.getCenterY()).transform(point, null);
        point = new Point((int) Math.round(local.getX()), (int) Math.round(local.getY()));
        if (!new Rectangle(rect.x - HANDLE_RADIUS, rect.y - HANDLE_RADIUS,
                rect.width + 2 * HANDLE_RADIUS + 1, rect.height + 2 * HANDLE_RADIUS + 1).contains(point)) return 0;
        int edges = 0;
        if (Math.abs(point.x - rect.x) <= HANDLE_RADIUS) edges |= LEFT;
        else if (Math.abs(point.x - rect.x - rect.width) <= HANDLE_RADIUS) edges |= RIGHT;
        if (Math.abs(point.y - rect.y) <= HANDLE_RADIUS) edges |= TOP;
        else if (Math.abs(point.y - rect.y - rect.height) <= HANDLE_RADIUS) edges |= BOTTOM;
        return edges;
    }

    private void bind(String key, String name, Runnable action) {
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key), name);
        getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                if (selecting && editable) action.run();
            }
        });
    }

    private Path2D polygonPath() {
        Path2D path = new Path2D.Double();
        for (int i = 0; i < vertices.size(); i++) {
            Point p = vertices.get(i);
            if (i == 0) path.moveTo(p.x, p.y); else path.lineTo(p.x, p.y);
        }
        if (closed) path.closePath();
        return path;
    }

    private static double polygonArea(List<Point> points) {
        double area = 0;
        for (int i = 0; i < points.size(); i++) {
            Point a = points.get(i), b = points.get((i + 1) % points.size());
            area += (double) a.x * b.y - (double) b.x * a.y;
        }
        return Math.abs(area) / 2;
    }

    private static boolean validPolygon(List<Point> points) {
        int n = points.size();
        if (n < 3 || polygonArea(points) < .5) return false;
        for (int i = 0; i < n; i++) {
            Point a = points.get(i), b = points.get((i + 1) % n);
            if (a.equals(b)) return false;
            for (int j = i + 1; j < n; j++) {
                if (j == i + 1 || (i == 0 && j == n - 1)) continue;
                Point c = points.get(j), d = points.get((j + 1) % n);
                if (Line2D.linesIntersect(a.x, a.y, b.x, b.y, c.x, c.y, d.x, d.y)) return false;
            }
        }
        return true;
    }

    private void closePolygon() {
        if (!polygonMode || closed) return;
        if (validPolygon(vertices)) closed = true;
        else Toolkit.getDefaultToolkit().beep();
        selectionChanged();
    }

    private int hitVertex(Point screen) {
        for (int i = 0; i < vertices.size(); i++) if (screenPoint(vertices.get(i)).distance(screen) <= 8) return i;
        return -1;
    }

    private void polygonPressed(MouseEvent event) {
        Point p = imagePoint(event.getPoint(), false);
        if (p == null) return;
        vertex = hitVertex(event.getPoint());
        if (!closed) {
            if (event.getClickCount() >= 2 || (vertex == 0 && vertices.size() >= 3)) closePolygon();
            else if (vertices.isEmpty() || !vertices.get(vertices.size() - 1).equals(p)) vertices.add(p);
            selectionChanged(); return;
        }
        if (vertex < 0 && event.getClickCount() >= 2) {
            for (int i = 0; i < vertices.size(); i++) {
                Point a = screenPoint(vertices.get(i)), b = screenPoint(vertices.get((i + 1) % vertices.size()));
                if (Line2D.ptSegDist(a.x, a.y, b.x, b.y, event.getX(), event.getY()) <= 8) {
                    List<Point> candidate = new ArrayList<>(vertices); candidate.add(i + 1, p);
                    if (validPolygon(candidate)) { vertices.clear(); vertices.addAll(candidate); vertex = i + 1; }
                    break;
                }
            }
        }
        if (vertex >= 0 || polygonPath().contains(p)) {
            dragStart = p; beforeVertices = new ArrayList<>(vertices); moving = vertex < 0;
        }
        selectionChanged();
    }

    private void polygonDragged(Point p) {
        List<Point> candidate = new ArrayList<>(beforeVertices);
        if (moving) {
            int minX = beforeVertices.stream().mapToInt(v -> v.x).min().getAsInt();
            int minY = beforeVertices.stream().mapToInt(v -> v.y).min().getAsInt();
            int maxX = beforeVertices.stream().mapToInt(v -> v.x).max().getAsInt();
            int maxY = beforeVertices.stream().mapToInt(v -> v.y).max().getAsInt();
            int dx = Math.max(-minX, Math.min(getImage().getWidth() - 1 - maxX, p.x - dragStart.x));
            int dy = Math.max(-minY, Math.min(getImage().getHeight() - 1 - maxY, p.y - dragStart.y));
            candidate.clear();
            for (Point old : beforeVertices) candidate.add(new Point(old.x + dx, old.y + dy));
        } else candidate.set(vertex, p);
        if (validPolygon(candidate)) { vertices.clear(); vertices.addAll(candidate); selectionChanged(); }
    }

    private void deleteVertex() {
        if (!polygonMode || vertices.isEmpty()) return;
        endDrag();
        int index = closed ? vertex : vertices.size() - 1;
        if (index < 0) return;
        List<Point> candidate = new ArrayList<>(vertices); candidate.remove(index);
        if (!closed || validPolygon(candidate)) { vertices.clear(); vertices.addAll(candidate); vertex = -1; selectionChanged(); }
    }

    private AffineTransform screenTransform() {
        Rectangle b = imageBounds();
        AffineTransform transform = AffineTransform.getTranslateInstance(b.x, b.y);
        transform.scale(b.width / (double) getImage().getWidth(), b.height / (double) getImage().getHeight());
        return transform;
    }

    private Point screenPoint(Point p) {
        Point2D point = screenTransform().transform(p, null);
        return new Point((int) Math.round(point.getX()), (int) Math.round(point.getY()));
    }

    private Point rotationHandle() {
        Rectangle rect = screenSelection();
        Point2D p = AffineTransform.getRotateInstance(angle, rect.getCenterX(), rect.getCenterY())
                .transform(new Point2D.Double(rect.getCenterX(), rect.y - 28), null);
        return new Point((int) Math.round(p.getX()), (int) Math.round(p.getY()));
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        if (getImage() == null || (selection == null && vertices.isEmpty())) return;
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            Shape shape = getShape();
            Shape screenShape = screenTransform().createTransformedShape(shape == null ? polygonPath() : shape);
            if (shape != null) {
                Area outside = new Area(imageBounds()); outside.subtract(new Area(screenShape));
                g.setColor(new Color(0, 0, 0, 130)); g.fill(outside);
            }
            g.setColor(Color.BLACK); g.setStroke(new BasicStroke(3)); g.draw(screenShape);
            g.setColor(Color.YELLOW); g.setStroke(new BasicStroke(1)); g.draw(screenShape);
            if (selecting && editable) {
                if (polygonMode) {
                    for (int i = 0; i < vertices.size(); i++) {
                        Point p = screenPoint(vertices.get(i));
                        g.setColor(i == vertex ? Color.ORANGE : Color.YELLOW); g.fillRect(p.x - 4, p.y - 4, 9, 9);
                    }
                } else {
                    Rectangle rect = screenSelection();
                    g.rotate(angle, rect.getCenterX(), rect.getCenterY());
                    for (int x : new int[]{rect.x, rect.x + rect.width / 2, rect.x + rect.width})
                        for (int y : new int[]{rect.y, rect.y + rect.height / 2, rect.y + rect.height}) {
                            if (x != rect.x + rect.width / 2 || y != rect.y + rect.height / 2) g.fillRect(x - 3, y - 3, 7, 7);
                        }
                    g.drawLine((int) rect.getCenterX(), rect.y, (int) rect.getCenterX(), rect.y - 28);
                    g.fillOval((int) rect.getCenterX() - 5, rect.y - 33, 10, 10);
                }
            }
        } finally { g.dispose(); }
    }
}
