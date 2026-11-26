package istu.quartz.ui;

import ij.ImagePlus;
import istu.quartz.model.SpatialScale;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.util.function.Consumer;

final class ScalePanel extends JPanel {
    private final JButton choose = new JButton("Задать по двум точкам…");
    private final JButton reset = new JButton("Сбросить масштаб");
    private final JLabel value = new JLabel("Масштаб не задан (px)");
    private final ImagePreview preview;
    private final Runnable activate;
    private final Consumer<SpatialScale> changed;
    private ImagePlus image;
    private SpatialScale scale;
    private Point first;
    private boolean picking;

    ScalePanel(ImagePreview preview, Runnable activate, Consumer<SpatialScale> changed) {
        super(new GridLayout(0, 1, 0, 6));
        this.preview = preview; this.activate = activate; this.changed = changed;
        setBorder(BorderFactory.createTitledBorder("Масштаб"));
        add(value); add(choose); add(reset);
        choose.addActionListener(e -> { if (picking) cancel(); else begin(); });
        reset.addActionListener(e -> setScale(null));
        preview.setFocusable(true);
        preview.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ESCAPE"), "cancelScale");
        preview.getActionMap().put("cancelScale", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { cancel(); }
        });
        preview.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (!picking || !SwingUtilities.isLeftMouseButton(e)) return;
                Point point = preview.imagePoint(e.getPoint(), false);
                if (point == null) return;
                if (first == null) { first = point; preview.setMeasurePoints(first, null); return; }
                if (first.equals(point)) return;
                preview.setMeasurePoints(first, point);
                enterDistance(first, point);
                cancel();
            }
        });
        setBusy(false);
    }

    SpatialScale getScale() { return scale; }
    void setImage(ImagePlus image) throws IOException {
        cancel(); this.image = image;
        setScale(image == null ? null : SpatialScale.fromImage(image));
        setBusy(false);
    }
    void setScale(SpatialScale next) {
        cancel(); scale = next;
        if (image != null) SpatialScale.apply(image, next);
        value.setText(next == null ? "Масштаб не задан (px)" : "1 px = "
                + String.format(java.util.Locale.ROOT, "%.6g", next.pixelSize) + " " + next.unit);
        value.setToolTipText(value.getText());
        preview.setSpatialScale(next);
        changed.accept(next);
        reset.setEnabled(image != null && next != null);
    }
    void setBusy(boolean busy) {
        if (busy) cancel();
        choose.setEnabled(!busy && image != null);
        reset.setEnabled(!busy && image != null && scale != null);
    }
    private void begin() {
        if (image == null) return;
        activate.run(); picking = true; first = null;
        choose.setText("Отменить выбор (Esc)");
        preview.setMeasurePoints(null, null);
        preview.requestFocusInWindow();
        preview.setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
        value.setText("Укажите два конца отрезка");
    }
    private void cancel() {
        picking = false; first = null;
        choose.setText("Задать по двум точкам…");
        preview.setMeasurePoints(null, null);
        preview.setCursor(Cursor.getDefaultCursor());
        if (scale != null) value.setText("1 px = " + String.format(java.util.Locale.ROOT, "%.6g", scale.pixelSize) + " " + scale.unit);
        else value.setText("Масштаб не задан (px)");
    }
    private void enterDistance(Point a, Point b) {
        double pixels = a.distance(b);
        JTextField distance = new JTextField("1");
        JComboBox<String> unit = new JComboBox<>(new String[]{"мкм", "мм", "см", "м"});
        unit.setEditable(true);
        if (scale != null) unit.setSelectedItem(scale.unit);
        JPanel fields = new JPanel(new GridLayout(0, 2, 8, 8));
        fields.add(new JLabel("На изображении:")); fields.add(new JLabel(String.format(java.util.Locale.ROOT, "%.3f px", pixels)));
        fields.add(new JLabel("Реальное расстояние:")); fields.add(distance);
        fields.add(new JLabel("Единица измерения:")); fields.add(unit);
        while (JOptionPane.showConfirmDialog(this, fields, "Масштаб по двум точкам", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            try {
                double number = Double.parseDouble(distance.getText().trim().replace(',', '.'));
                setScale(SpatialScale.between(a.x, a.y, b.x, b.y, number, unit.getEditor().getItem().toString()));
                return;
            } catch (RuntimeException error) {
                JOptionPane.showMessageDialog(this, "Введите положительное число и единицу измерения.", "Масштаб", JOptionPane.ERROR_MESSAGE);
            }
        }
    }
}
