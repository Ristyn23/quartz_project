package istu.quartz.ui;

import ij.ImagePlus;
import istu.quartz.model.SpatialScale;
import ij.io.FileSaver;
import ij.io.Opener;
import istu.quartz.model.StackMerger;
import org.scijava.log.LogService;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

/** Independent preparation window, accessible directly from the Fiji Plugins menu. */
public final class StackMergeWindow extends JFrame {
    private static final FileNameExtensionFilter IMAGE_FILES = new FileNameExtensionFilter(
            "Изображения (BMP, PNG, TIFF, JPEG)", "bmp", "png", "tif", "tiff", "jpg", "jpeg");
    private final ScalePanel scalePanel;
    private final LogService log;
    private final DefaultListModel<File> files = new DefaultListModel<>();
    private final JList<File> list = new JList<>(files);
    private final List<ImagePlus> inputs = new ArrayList<>();
    private final JButton add = new JButton("Добавить снимки…");
    private final JButton remove = new JButton("Удалить");
    private final JButton up = new JButton("Выше");
    private final JButton down = new JButton("Ниже");
    private final JButton align = new JButton("Совместить");
    private final JButton merge = new JButton("Объединить");
    private final JButton use = new JButton("Использовать в Quartz");
    private final JButton save = new JButton("Сохранить TIFF…");
    private final JSlider blend = new JSlider(0, 100, 50);
    private final ImagePreview sourcePreview = new ImagePreview();
    private final ImagePreview alignedPreview = new ImagePreview();
    private final ImagePreview resultPreview = new ImagePreview();
    private final JTabbedPane tabs = new JTabbedPane();
    private final JLabel status = new JLabel("Добавьте снимки одной области в порядке съёмки. Первый кадр — опорный.");
    private final JLabel details = new JLabel(" ");
    private final JProgressBar progress = new JProgressBar();
    private StackMerger.Alignment alignment;
    private ImagePlus result;
    private SwingWorker<?, ?> worker;
    private boolean busy;

    public StackMergeWindow(LogService log) {
        super("Quartz Segmentation — Объединить снимки");
        this.log = log;
        scalePanel = new ScalePanel(sourcePreview, () -> { list.setSelectedIndex(0); tabs.setSelectedIndex(0); }, scale -> {
            for (ImagePlus input : inputs) SpatialScale.apply(input, scale);
            alignedPreview.setSpatialScale(scale); resultPreview.setSpatialScale(scale);
            if (result != null) SpatialScale.apply(result, scale);
        });
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> view, Object value, int index,
                                                          boolean selected, boolean focus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(view, value, index, selected, focus);
                label.setText((index + 1) + ". " + ((File) value).getName());
                label.setToolTipText(((File) value).getAbsolutePath());
                return label;
            }
        });
        JPanel fileTools = new JPanel(new FlowLayout(FlowLayout.LEADING));
        fileTools.add(add);
        fileTools.add(remove);
        fileTools.add(up);
        fileTools.add(down);
        JPanel filePanel = new JPanel(new BorderLayout());
        filePanel.add(fileTools, BorderLayout.PAGE_START);
        filePanel.add(new JScrollPane(list), BorderLayout.CENTER);
        filePanel.add(scalePanel, BorderLayout.PAGE_END);
        filePanel.setPreferredSize(new Dimension(390, 400));

        JPanel aligned = new JPanel(new BorderLayout());
        JPanel overlayTools = new JPanel(new BorderLayout(8, 0));
        overlayTools.add(new JLabel("Первый кадр"), BorderLayout.LINE_START);
        overlayTools.add(blend, BorderLayout.CENTER);
        overlayTools.add(new JLabel("Выбранный кадр"), BorderLayout.LINE_END);
        JPanel alignmentInfo = new JPanel(new GridLayout(0, 1));
        alignmentInfo.add(overlayTools);
        alignmentInfo.add(details);
        aligned.add(alignmentInfo, BorderLayout.PAGE_START);
        aligned.add(alignedPreview, BorderLayout.CENTER);
        tabs.addTab("Исходные кадры", sourcePreview);
        tabs.addTab("Проверка совмещения", aligned);
        tabs.addTab("Объединённый снимок", resultPreview);
        tabs.setEnabledAt(1, false);
        tabs.setEnabledAt(2, false);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, filePanel, tabs);
        split.setResizeWeight(0.3);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEADING));
        actions.add(align);
        actions.add(merge);
        actions.add(use);
        actions.add(save);
        JPanel footer = new JPanel(new BorderLayout(4, 4));
        footer.add(actions, BorderLayout.PAGE_START);
        footer.add(status, BorderLayout.CENTER);
        footer.add(progress, BorderLayout.PAGE_END);
        progress.setVisible(false);
        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        content.add(split, BorderLayout.CENTER);
        content.add(footer, BorderLayout.PAGE_END);
        setContentPane(content);

        TransferHandler transfer = new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                if (busy || !support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return false;
                if (support.isDrop()) {
                    if ((support.getSourceDropActions() & COPY) == 0) return false;
                    support.setDropAction(COPY);
                }
                return true;
            }

            @Override
            public boolean importData(TransferSupport support) {
                if (!canImport(support)) return false;
                try {
                    List<?> dropped = (List<?>) support.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    List<File> selected = new ArrayList<>();
                    for (Object value : dropped) {
                        if (!(value instanceof File)) throw new IOException("Перетащите файлы изображений.");
                        selected.add((File) value);
                    }
                    addFiles(selected);
                    return true;
                } catch (Exception error) {
                    showError(error);
                    return false;
                }
            }
        };
        setTransferHandler(transfer);
        list.setTransferHandler(transfer);
        sourcePreview.setTransferHandler(transfer);
        alignedPreview.setTransferHandler(transfer);
        resultPreview.setTransferHandler(transfer);
        add.addActionListener(e -> chooseFiles());
        remove.addActionListener(e -> removeSelected());
        up.addActionListener(e -> move(-1));
        down.addActionListener(e -> move(1));
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                refreshPreview();
                updateActions();
            }
        });
        blend.addChangeListener(e -> refreshOverlay());
        align.addActionListener(e -> align());
        merge.addActionListener(e -> merge());
        save.addActionListener(e -> save());
        use.addActionListener(e -> new QuartzWindow(log, result.duplicate()).setVisible(true));
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                if (busy && JOptionPane.showConfirmDialog(StackMergeWindow.this,
                        "Прервать операцию и закрыть окно?", "Quartz Segmentation",
                        JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
                if (worker != null) worker.cancel(true);
                dispose();
            }
        });
        setMinimumSize(new Dimension(1000, 540));
        setSize(1220, 760);
        setLocationByPlatform(true);
        updateActions();
    }

    private void chooseFiles() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Добавить снимки одной области");
        chooser.setFileFilter(IMAGE_FILES);
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setMultiSelectionEnabled(true);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            addFiles(java.util.Arrays.asList(chooser.getSelectedFiles()));
        }
    }

    private void addFiles(List<File> selected) {
        if (selected.isEmpty() || busy) return;
        List<File> pending = new ArrayList<>();
        try {
            for (File file : selected) {
                if (!file.isFile() || !IMAGE_FILES.accept(file)) {
                    throw new IOException("Нужны файлы BMP, PNG, TIFF или JPEG: " + file.getName());
                }
                File canonical = file.getCanonicalFile();
                if (files.contains(canonical) || pending.contains(canonical)) {
                    throw new IOException("Снимок уже добавлен: " + file.getName());
                }
                pending.add(canonical);
            }
        } catch (IOException error) {
            showError(error);
            return;
        }
        setBusy(true, "Открытие снимков…");
        worker = new SwingWorker<List<ImagePlus>, Void>() {
            @Override
            protected List<ImagePlus> doInBackground() throws Exception {
                List<ImagePlus> loaded = new ArrayList<>();
                int width = inputs.isEmpty() ? -1 : inputs.get(0).getWidth();
                int height = inputs.isEmpty() ? -1 : inputs.get(0).getHeight();
                Opener opener = new Opener();
                opener.setSilentMode(true);
                for (File file : pending) {
                    if (isCancelled()) throw new CancellationException();
                    ImagePlus input = opener.openImage(file.getAbsolutePath());
                    if (input == null || input.getStackSize() != 1 || input.getType() != ImagePlus.COLOR_RGB) {
                        throw new IOException("Нужен одиночный RGB-снимок: " + file.getName());
                    }
                    if (width < 0) { width = input.getWidth(); height = input.getHeight(); }
                    if (input.getWidth() != width || input.getHeight() != height) {
                        throw new IOException("Все снимки должны иметь одинаковый размер: " + file.getName());
                    }
                    SpatialScale reference = inputs.isEmpty()
                            ? (loaded.isEmpty() ? SpatialScale.fromImage(input) : SpatialScale.fromImage(loaded.get(0)))
                            : SpatialScale.fromImage(inputs.get(0));
                    SpatialScale incoming = SpatialScale.fromImage(input);
                    if (reference != null && incoming != null && !reference.equals(incoming))
                        throw new IOException("Масштабы кадров различаются. Для объединения нужны кадры одного масштаба.");
                    loaded.add(input);
                }
                return loaded;
            }

            @Override
            protected void done() {
                if (!isDisplayable()) return;
                try {
                    List<ImagePlus> loaded = get();
                    int first = inputs.size();
                    inputs.addAll(loaded);
                    invalidateResult();
                    for (File file : pending) files.addElement(file);
                    list.setSelectedIndex(first);
                    status.setText("Кадров: " + inputs.size() + ". Проверьте порядок и нажмите «Совместить».");
                } catch (Exception error) { showError(error); }
                finally { setBusy(false, null); }
            }
        };
        worker.execute();
    }

    private void removeSelected() {
        int index = list.getSelectedIndex();
        if (index < 0) return;
        inputs.remove(index);
        invalidateResult();
        files.remove(index);
        list.setSelectedIndex(Math.min(index, files.size() - 1));
        refreshPreview();
        status.setText("Состав стека изменён. Выполните совмещение заново.");
        updateActions();
    }

    private void move(int offset) {
        int index = list.getSelectedIndex();
        int next = index + offset;
        if (index < 0 || next < 0 || next >= files.size()) return;
        java.util.Collections.swap(inputs, index, next);
        invalidateResult();
        File file = files.get(index);
        files.set(index, files.get(next));
        files.set(next, file);
        list.setSelectedIndex(next);
        status.setText("Порядок кадров изменён. Выполните совмещение заново.");
        updateActions();
    }

    private void invalidateResult() {
        alignment = null;
        result = null;
        tabs.setSelectedIndex(0);
        tabs.setEnabledAt(1, false);
        tabs.setEnabledAt(2, false);
        alignedPreview.setImage(null);
        resultPreview.setImage(null);
        details.setText(" ");
    }

    private void align() {
        setBusy(true, "Поиск соответствий SIFT…");
        List<ImagePlus> snapshot = new ArrayList<>(inputs);
        worker = new SwingWorker<StackMerger.Alignment, String>() {
            @Override
            protected StackMerger.Alignment doInBackground() throws Exception {
                return new StackMerger().align(snapshot, message -> publish(message));
            }

            @Override
            protected void process(List<String> messages) {
                if (isDisplayable()) status.setText(messages.get(messages.size() - 1));
            }

            @Override
            protected void done() {
                if (!isDisplayable()) return;
                try {
                    alignment = get();
                    result = null;
                    resultPreview.setImage(null);
                    tabs.setEnabledAt(2, false);
                    tabs.setEnabledAt(1, true);
                    list.setSelectedIndex(Math.min(1, files.size() - 1));
                    refreshOverlay();
                    tabs.setSelectedIndex(1);
                    Rectangle bounds = alignment.bounds();
                    status.setText("Проверьте наложение. Вся область кадров: " + bounds.width + " × "
                            + bounds.height + " px. Затем нажмите «Объединить».");
                } catch (Exception error) { showError(error); }
                finally { setBusy(false, null); }
            }
        };
        worker.execute();
    }

    private void merge() {
        setBusy(true, "Усреднение снимков по всей области кадров…");
        worker = new SwingWorker<ImagePlus, Void>() {
            @Override
            protected ImagePlus doInBackground() { return alignment.average(); }

            @Override
            protected void done() {
                if (!isDisplayable()) return;
                try {
                    result = get();
                    SpatialScale.apply(result, scalePanel.getScale());
                    resultPreview.setImage(result.getBufferedImage());
                    tabs.setEnabledAt(2, true);
                    tabs.setSelectedIndex(2);
                    status.setText("Готово: " + result.getWidth() + " × " + result.getHeight()
                            + " px. Сохранена вся область всех кадров.");
                } catch (Exception error) { showError(error); }
                finally { setBusy(false, null); }
            }
        };
        worker.execute();
    }

    private void save() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Сохранить объединённый снимок");
        chooser.setFileFilter(new FileNameExtensionFilter("TIFF (*.tif, *.tiff)", "tif", "tiff"));
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setSelectedFile(new File("quartz-merged.tif"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File selected = chooser.getSelectedFile();
        if (!selected.getName().toLowerCase(java.util.Locale.ROOT).matches(".*\\.tiff?")) {
            selected = new File(selected.getParentFile(), selected.getName() + ".tif");
        }
        Path output = selected.toPath().toAbsolutePath();
        boolean replace = Files.exists(output);
        if (replace && JOptionPane.showConfirmDialog(this, "Заменить " + selected.getName() + "?",
                "Сохранить TIFF", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        ImagePlus snapshot = result.duplicate();
        setBusy(true, "Сохранение TIFF…");
        worker = new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                Path temporary = Files.createTempFile(output.getParent(), ".quartz-merge-", ".tif");
                try {
                    if (!new FileSaver(snapshot).saveAsTiff(temporary.toString())) {
                        throw new IOException("Не удалось сохранить TIFF.");
                    }
                    if (isCancelled()) throw new CancellationException();
                    if (replace) Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
                    else Files.move(temporary, output);
                } finally { Files.deleteIfExists(temporary); }
                return null;
            }

            @Override
            protected void done() {
                if (!isDisplayable()) return;
                try { get(); status.setText("Сохранено: " + output); }
                catch (Exception error) { showError(error); }
                finally { setBusy(false, null); }
            }
        };
        worker.execute();
    }

    private void refreshPreview() {
        int index = list.getSelectedIndex();
        sourcePreview.setImage(index >= 0 && index < inputs.size() ? inputs.get(index).getBufferedImage() : null);
        try { scalePanel.setImage(inputs.isEmpty() ? null : inputs.get(0)); }
        catch (IOException error) { showError(error); }
        refreshOverlay();
    }

    private void refreshOverlay() {
        int index = list.getSelectedIndex();
        if (alignment == null || index < 0 || index >= alignment.size()) return;
        BufferedImage first = alignment.frame(0).getBufferedImage();
        BufferedImage selected = alignment.frame(index).getBufferedImage();
        BufferedImage overlay = new BufferedImage(first.getWidth(), first.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = overlay.createGraphics();
        try {
            g.drawImage(first, 0, 0, null);
            g.setComposite(AlphaComposite.SrcOver.derive(blend.getValue() / 100f));
            g.drawImage(selected, 0, 0, null);
        } finally { g.dispose(); }
        // Keep the current zoom while changing the blend slider.
        if (alignedPreview.getImage() == null) alignedPreview.setImage(first);
        alignedPreview.setOverlay(overlay);
        details.setText(alignment.description(index));
        details.setToolTipText(alignment.description(index));
    }

    private void setBusy(boolean value, String message) {
        busy = value;
        scalePanel.setBusy(value);
        if (message != null) status.setText(message);
        progress.setIndeterminate(value);
        progress.setVisible(value);
        updateActions();
    }

    private void updateActions() {
        int selected = list.getSelectedIndex();
        add.setEnabled(!busy);
        list.setEnabled(!busy);
        remove.setEnabled(!busy && selected >= 0);
        up.setEnabled(!busy && selected > 0);
        down.setEnabled(!busy && selected >= 0 && selected < files.size() - 1);
        align.setEnabled(!busy && inputs.size() >= 2);
        merge.setEnabled(!busy && alignment != null);
        use.setEnabled(!busy && result != null);
        save.setEnabled(!busy && result != null);
        blend.setEnabled(!busy && alignment != null);
    }

    private void showError(Throwable error) {
        if (error instanceof ExecutionException && error.getCause() != null) error = error.getCause();
        if (error instanceof CancellationException) return;
        log.error(error);
        status.setText("Операция не выполнена.");
        JOptionPane.showMessageDialog(this, error.getMessage() == null ? error.toString() : error.getMessage(),
                "Quartz Segmentation", JOptionPane.ERROR_MESSAGE);
    }
}
