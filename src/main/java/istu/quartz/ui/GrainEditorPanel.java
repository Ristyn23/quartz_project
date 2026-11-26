package istu.quartz.ui;

import ij.process.ImageProcessor;
import istu.quartz.model.GrainCsvExporter;
import istu.quartz.model.GrainLabelMap;
import istu.quartz.model.GrainMeasurement;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;

final class GrainEditorPanel extends JPanel {

    private final JButton merge = new JButton("Объединить");
    private final JToggleButton split = new JToggleButton("Разделить линией");
    private final JButton save = new JButton("Сохранить CSV…");
    private final JButton saveAnnotations = new JButton("Сохранить разметку…");
    private final JTextArea instruction = new JTextArea(3, 20);
    private final JLabel firstMeasurement = new JLabel(" ");
    private final JLabel secondMeasurement = new JLabel(" ");
    private final NumberFormat areaFormat = NumberFormat.getIntegerInstance(Locale.forLanguageTag("ru-RU"));
    private final NumberFormat shapeFormat = NumberFormat.getNumberInstance(Locale.forLanguageTag("ru-RU"));
    private final List<Point> stroke = new ArrayList<>();
    private final EditorPreview preview;
    private final JPanel sidebar = new SidebarPanel();
    private final JScrollPane sidebarScroll = new JScrollPane(sidebar);

    private GrainLabelMap labels;
    private BufferedImage overlay;
    private int firstLabel;
    private int secondLabel;
    private boolean exporting;
    private boolean dirty;
    private boolean busy;
    private boolean editingEnabled = true;

    GrainEditorPanel() {
        this(new ImagePreview.ViewState());
    }

    GrainEditorPanel(ImagePreview.ViewState view) {
        super(new BorderLayout());
        preview = new EditorPreview(view);
        JPanel tools = new JPanel(new GridLayout(0, 1, 0, 6));
        tools.setBorder(BorderFactory.createTitledBorder("Редактирование"));
        tools.add(merge);
        tools.add(split);
        JPanel exportTools = new JPanel(new GridLayout(0, 1, 0, 6));
        exportTools.setBorder(BorderFactory.createTitledBorder("Сохранение"));
        exportTools.add(save);
        exportTools.add(saveAnnotations);
        instruction.setEditable(false);
        instruction.setFocusable(false);
        instruction.setLineWrap(true);
        instruction.setWrapStyleWord(true);
        instruction.setOpaque(false);
        instruction.setFont(firstMeasurement.getFont());
        firstMeasurement.setVerticalAlignment(JLabel.TOP);
        secondMeasurement.setVerticalAlignment(JLabel.TOP);
        JPanel measurements = new JPanel(new BorderLayout(0, 8));
        measurements.setBorder(BorderFactory.createTitledBorder("Выбранные зёрна"));
        measurements.add(instruction, BorderLayout.PAGE_START);
        JPanel values = new JPanel(new BorderLayout(0, 12));
        values.add(firstMeasurement, BorderLayout.PAGE_START);
        values.add(secondMeasurement, BorderLayout.CENTER);
        measurements.add(values, BorderLayout.CENTER);
        sidebar.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        sidebar.add(tools, BorderLayout.PAGE_START);
        sidebar.add(measurements, BorderLayout.CENTER);
        sidebar.add(exportTools, BorderLayout.PAGE_END);
        sidebarScroll.setPreferredSize(new Dimension(290, 400));
        sidebarScroll.setMinimumSize(new Dimension(230, 100));
        sidebarScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        add(preview, BorderLayout.CENTER);

        merge.addActionListener(event -> mergeSelected());
        save.addActionListener(event -> saveCsv());
        saveAnnotations.addActionListener(event -> saveAnnotations());
        split.addActionListener(event -> {
            if (secondLabel != 0) {
                firstLabel = 0;
                secondLabel = 0;
            }
            stroke.clear();
            updateSelection();
        });
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                if (busy || !editingEnabled || labels == null || !SwingUtilities.isLeftMouseButton(event)) {
                    return;
                }
                Point point = preview.imagePoint(event.getPoint(), false);
                if (point == null) {
                    return;
                }
                if (split.isSelected() && firstLabel != 0) {
                    stroke.clear();
                    stroke.add(point);
                } else {
                    select(labels.labelAt(point.x, point.y));
                }
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                if ((event.getModifiersEx() & MouseEvent.BUTTON1_DOWN_MASK) != 0) extendStroke(event);
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                if (!SwingUtilities.isLeftMouseButton(event) || stroke.isEmpty()) {
                    return;
                }
                extendStroke(event);
                if (stroke.size() == 1) {
                    Point clicked = stroke.get(0);
                    firstLabel = labels.labelAt(clicked.x, clicked.y);
                    stroke.clear();
                    updateSelection();
                    return;
                }
                int newLabel = labels.split(firstLabel, stroke);
                stroke.clear();
                if (newLabel > 0) {
                    dirty = true;
                    secondLabel = newLabel;
                    split.setSelected(false);
                    updateSelection();
                    instruction.setText("Зерно разделено.");
                } else {
                    preview.repaint();
                    instruction.setText("Проведите одну линию от края до края выбранного зерна.");
                }
            }
        };
        preview.addMouseListener(mouse);
        preview.addMouseMotionListener(mouse);
        setGrains(null, null);
    }

    void setGrains(BufferedImage source, GrainLabelMap map) {
        labels = map;
        dirty = false;
        firstLabel = 0;
        secondLabel = 0;
        stroke.clear();
        split.setSelected(false);
        preview.setImage(source);
        overlay = map == null ? null : new BufferedImage(map.getWidth(), map.getHeight(), BufferedImage.TYPE_INT_ARGB);
        updateSelection();
    }

    private void select(int label) {
        if (label == 0) {
            return;
        }
        if (label == firstLabel) {
            firstLabel = secondLabel;
            secondLabel = 0;
        } else if (label == secondLabel) {
            secondLabel = 0;
        } else if (firstLabel == 0) {
            firstLabel = label;
        } else {
            secondLabel = label;
        }
        updateSelection();
    }

    private void extendStroke(MouseEvent event) {
        if (stroke.isEmpty()) {
            return;
        }
        Point point = preview.imagePoint(event.getPoint(), true);
        if (point != null && !point.equals(stroke.get(stroke.size() - 1))) {
            stroke.add(point);
            preview.repaint();
        }
    }

    private void mergeSelected() {
        if (!labels.merge(firstLabel, secondLabel)) {
            instruction.setText("Выбранные зёрна не соседствуют. Выберите другую пару.");
            return;
        }
        dirty = true;
        secondLabel = 0;
        updateSelection();
        instruction.setText("Зёрна объединены.");
    }

    private void updateSelection() {
        setBusy(busy);
        if (labels == null) {
            instruction.setText("");
            firstMeasurement.setText(" ");
            secondMeasurement.setText(" ");
            return;
        }
        if (split.isSelected()) {
            instruction.setText(firstLabel == 0 ? "Выберите зерно." : "Проведите линию от края до края зерна.");
        } else if (secondLabel != 0) {
            instruction.setText("Зёрна " + firstLabel + " и " + secondLabel + " выбраны.");
        } else if (firstLabel != 0) {
            instruction.setText("Выберите второе зерно.");
        } else {
            instruction.setText("Выберите два зерна для объединения.");
        }
        firstMeasurement.setText(formatMeasurement(firstLabel));
        secondMeasurement.setText(formatMeasurement(secondLabel));
        drawOverlay();
    }

    void setBusy(boolean busy) {
        this.busy = busy;
        split.setEnabled(!busy && editingEnabled && labels != null);
        merge.setEnabled(!busy && editingEnabled && labels != null && !split.isSelected() && secondLabel != 0);
        save.setEnabled(!busy && labels != null && !exporting);
        saveAnnotations.setEnabled(!busy && labels != null);
        if (busy) {
            stroke.clear();
            preview.repaint();
        }
    }

    JComponent toolsPanel() { return sidebarScroll; }

    void setEditingEnabled(boolean enabled) {
        editingEnabled = enabled;
        stroke.clear();
        preview.repaint();
        setBusy(busy);
    }

    private String formatMeasurement(int label) {
        if (label == 0) {
            return " ";
        }
        GrainMeasurement grain = labels.measure(label);
        return "<html><b>Зерно №" + label + "</b><br>Площадь: " + areaFormat.format(grain.areaPx2) + " px²"
                + "<br>Вытянутость: " + formatShape(grain.aspectRatio)
                + "<br>Круглость: " + formatShape(grain.circularity) + "</html>";
    }

    private String formatShape(double value) {
        return Double.isFinite(value) ? shapeFormat.format(value) : "н/д";
    }

    boolean confirmDiscard() {
        if (!dirty) {
            return true;
        }
        int answer = JOptionPane.showConfirmDialog(this, "Сохранить изменения разметки?",
                "Quartz", JOptionPane.YES_NO_CANCEL_OPTION);
        if (answer == JOptionPane.YES_OPTION) {
            return saveAnnotations();
        }
        return answer == JOptionPane.NO_OPTION;
    }

    private boolean saveAnnotations() {
        if (labels != null && AnnotationFileDialog.save(this, labels.snapshot())) {
            dirty = false;
            instruction.setText("Разметка сохранена.");
            return true;
        }
        return false;
    }

    private void saveCsv() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Сохранить измерения зёрен");
        chooser.setFileFilter(new FileNameExtensionFilter("Таблица CSV (*.csv)", "csv"));
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setSelectedFile(new File("measurements.csv"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }

        File file = chooser.getSelectedFile();
        if (!file.getName().toLowerCase(Locale.ROOT).endsWith(".csv")) {
            file = new File(file.getParentFile(), file.getName() + ".csv");
        }
        Path destination = file.toPath();
        boolean replace = Files.exists(destination);
        if (replace && JOptionPane.showConfirmDialog(this, "Заменить " + file.getName() + "?",
                "Сохранить CSV", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
            return;
        }

        GrainLabelMap source = labels;
        ImageProcessor snapshot = source.snapshot();
        exporting = true;
        save.setEnabled(false);
        instruction.setText("Сохранение CSV…");
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws IOException {
                GrainCsvExporter.write(snapshot, destination, replace);
                return null;
            }

            @Override
            protected void done() {
                exporting = false;
                save.setEnabled(!busy && labels != null);
                if (!isDisplayable()) {
                    return;
                }
                try {
                    get();
                    if (labels == source) {
                        instruction.setText("CSV сохранён: " + destination.getFileName());
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    showExportError(error);
                } catch (ExecutionException error) {
                    showExportError(error.getCause());
                }
            }
        }.execute();
    }

    private void showExportError(Throwable error) {
        instruction.setText("Не удалось сохранить CSV.");
        JOptionPane.showMessageDialog(this, error.toString(), "Сохранить CSV", JOptionPane.ERROR_MESSAGE);
    }

    private void drawOverlay() {
        int width = overlay.getWidth();
        int height = overlay.getHeight();
        int[] pixels = ((DataBufferInt) overlay.getRaster().getDataBuffer()).getData();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int label = labels.labelAt(x, y);
                int color = 0;
                if (label != 0) {
                    boolean boundary = labels.labelAt(x - 1, y) != label || labels.labelAt(x + 1, y) != label
                            || labels.labelAt(x, y - 1) != label || labels.labelAt(x, y + 1) != label;
                    if (label == firstLabel || label == secondLabel) {
                        color = boundary ? 0xffffb43a : 0x60ffb43a;
                    } else if (boundary) {
                        color = 0xcc54dae9;
                    }
                }
                pixels[y * width + x] = color;
            }
        }
        preview.setOverlay(overlay);
    }

    private final class EditorPreview extends ImagePreview {
        private EditorPreview(ViewState view) {
            super(view);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Rectangle bounds = imageBounds();
            if (stroke.size() < 2 || bounds.isEmpty()) {
                return;
            }

            double scaleX = (double) bounds.width / getImage().getWidth();
            double scaleY = (double) bounds.height / getImage().getHeight();
            Path2D path = new Path2D.Double();
            Point start = stroke.get(0);
            path.moveTo(bounds.x + (start.x + 0.5) * scaleX, bounds.y + (start.y + 0.5) * scaleY);
            for (int i = 1; i < stroke.size(); i++) {
                Point point = stroke.get(i);
                path.lineTo(bounds.x + (point.x + 0.5) * scaleX, bounds.y + (point.y + 0.5) * scaleY);
            }
            Graphics2D canvas = (Graphics2D) graphics.create();
            try {
                canvas.setColor(Color.WHITE);
                canvas.setStroke(new BasicStroke(2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                canvas.draw(path);
            } finally {
                canvas.dispose();
            }
        }
    }

    private static final class SidebarPanel extends JPanel implements Scrollable {
        private SidebarPanel() { super(new BorderLayout(0, 12)); }
        @Override
        public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 16; }
        @Override
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return Math.max(16, visible.height - 16);
        }
        @Override
        public boolean getScrollableTracksViewportWidth() { return true; }
        @Override
        public boolean getScrollableTracksViewportHeight() {
            return getParent() != null && getPreferredSize().height <= getParent().getHeight();
        }
    }
}
