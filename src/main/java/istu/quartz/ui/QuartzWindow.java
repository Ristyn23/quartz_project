package istu.quartz.ui;

import ij.ImagePlus;
import ij.io.Opener;
import ij.process.ImageProcessor;
import istu.quartz.model.GrainBoundarySegmenter;
import istu.quartz.model.GrainLabelMap;
import istu.quartz.model.GrainWatershed;
import org.scijava.log.LogService;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.SwingWorker;
import javax.swing.TransferHandler;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;

public class QuartzWindow extends JFrame {

    private static final FileNameExtensionFilter IMAGE_FILES = new FileNameExtensionFilter(
            "Изображения (BMP, PNG, TIFF, JPEG)", "bmp", "png", "tif", "tiff", "jpg", "jpeg");
    private static final FileNameExtensionFilter MODEL_FILES =
            new FileNameExtensionFilter("Модели Weka (*.model)", "model");

    private final LogService log;
    private final GrainBoundarySegmenter segmenter = new GrainBoundarySegmenter();
    private final GrainWatershed watershed = new GrainWatershed();
    private final JButton openImage = new JButton("Открыть изображение");
    private final JButton chooseModel = new JButton("Выбрать модель");
    private final JButton analyze = new JButton("Обработать");
    private final JLabel modelName = new JLabel("Модель не выбрана");
    private final JLabel status = new JLabel("Перетащите снимок и модель в окно или выберите их кнопками.");
    private final JTabbedPane tabs = new JTabbedPane();
    private final ImagePreview originalPreview = new ImagePreview();
    private final ImagePreview boundaryPreview = new ImagePreview();
    private final GrainEditorPanel grainEditor = new GrainEditorPanel();

    private ImagePlus image;
    private File modelFile;
    private boolean processing;

    public QuartzWindow(LogService log) {
        super("Quartz");
        this.log = log;

        JPanel selection = new JPanel(new FlowLayout(FlowLayout.LEADING));
        selection.add(openImage);
        selection.add(chooseModel);
        selection.add(modelName);

        JPanel toolbar = new JPanel(new BorderLayout(8, 0));
        toolbar.add(selection, BorderLayout.CENTER);
        toolbar.add(analyze, BorderLayout.LINE_END);

        tabs.addTab("Снимок", originalPreview);
        tabs.addTab("Карта границ", boundaryPreview);
        tabs.addTab("Зёрна", grainEditor);
        tabs.setEnabledAt(1, false);
        tabs.setEnabledAt(2, false);

        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        content.add(toolbar, BorderLayout.PAGE_START);
        content.add(tabs, BorderLayout.CENTER);
        content.add(status, BorderLayout.PAGE_END);
        setContentPane(content);
        setTransferHandler(new InputFileTransferHandler());

        openImage.addActionListener(event -> chooseInput("Открыть изображение", IMAGE_FILES));
        chooseModel.addActionListener(event -> chooseInput("Выбрать модель Weka", MODEL_FILES));
        analyze.addActionListener(event -> analyze());
        analyze.setEnabled(false);

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(680, 420));
        setSize(960, 680);
        setLocationByPlatform(true);
    }

    private void chooseInput(String title, FileNameExtensionFilter filter) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(title);
        chooser.setFileFilter(filter);
        chooser.setAcceptAllFileFilterUsed(false);
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }

        try {
            File selected = chooser.getSelectedFile();
            if (!filter.accept(selected)) {
                throw new IllegalArgumentException("Этот тип файла нельзя выбрать в данном окне.");
            }
            loadInputs(Collections.singletonList(selected));
        } catch (RuntimeException error) {
            showError(error);
        }
    }

    private void loadInputs(List<?> files) {
        if (files.isEmpty() || files.size() > 2) {
            throw new IllegalArgumentException("Можно загрузить один снимок и одну модель .model.");
        }

        File selectedImage = null;
        File selectedModel = null;
        for (Object item : files) {
            if (!(item instanceof File) || !((File) item).isFile()) {
                throw new IllegalArgumentException("Выберите файлы снимка или модели.");
            }
            File file = (File) item;
            if (IMAGE_FILES.accept(file)) {
                if (selectedImage != null) {
                    throw new IllegalArgumentException("Можно загрузить только один снимок за раз.");
                }
                selectedImage = file;
            } else if (MODEL_FILES.accept(file)) {
                if (selectedModel != null) {
                    throw new IllegalArgumentException("Можно загрузить только одну модель за раз.");
                }
                selectedModel = file;
            } else {
                throw new IllegalArgumentException("Неподдерживаемый тип файла: " + file.getName());
            }
        }

        // Open the image before replacing either input in a combined transfer.
        if (selectedImage != null) {
            Opener opener = new Opener();
            opener.setSilentMode(true);
            ImagePlus openedImage = opener.openImage(selectedImage.getAbsolutePath());
            if (openedImage == null || openedImage.getStackSize() != 1) {
                throw new IllegalArgumentException("Не удалось открыть одиночное изображение: " + selectedImage.getName());
            }
            originalPreview.setImage(openedImage.getBufferedImage());
            image = openedImage;
        }
        if (selectedModel != null) {
            modelFile = selectedModel;
            modelName.setText(selectedModel.getName());
            modelName.setToolTipText(selectedModel.getAbsolutePath());
        }
        clearResult();
        status.setText(image == null ? "Загрузите снимок."
                : image.getTitle() + "  ·  " + image.getWidth() + " × " + image.getHeight());
        setProcessing(false);
    }

    private final class InputFileTransferHandler extends TransferHandler {
        @Override
        public boolean canImport(TransferSupport support) {
            if (processing || !support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                return false;
            }
            if (support.isDrop()) {
                if ((support.getSourceDropActions() & COPY) == 0) {
                    return false;
                }
                support.setDropAction(COPY);
            }
            return true;
        }

        @Override
        public boolean importData(TransferSupport support) {
            if (!canImport(support)) {
                return false;
            }
            try {
                List<?> files = (List<?>) support.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                loadInputs(files);
                return true;
            } catch (IOException | UnsupportedFlavorException | RuntimeException error) {
                log.error(error);
                String message = error.getMessage();
                status.setText(message == null ? "Не удалось принять файлы." : message);
                return false;
            }
        }
    }

    private void analyze() {
        ImagePlus input = image;
        File selectedModel = modelFile;
        clearResult();
        setProcessing(true);
        status.setText("TWS обрабатывает снимок…");

        new SwingWorker<GrainLabelMap, BufferedImage>() {
            @Override
            protected GrainLabelMap doInBackground() throws Exception {
                ImageProcessor boundary = segmenter.segment(input, selectedModel);
                boundary.setMinAndMax(0, 1);
                publish(boundary.getBufferedImage());
                return watershed.label(boundary);
            }

            @Override
            protected void process(List<BufferedImage> previews) {
                if (isDisplayable()) {
                    boundaryPreview.setImage(previews.get(previews.size() - 1));
                    tabs.setEnabledAt(1, true);
                    tabs.setSelectedIndex(1);
                    status.setText("Watershed выделяет зёрна…");
                }
            }

            @Override
            protected void done() {
                if (!isDisplayable()) {
                    return;
                }
                try {
                    grainEditor.setGrains(originalPreview.getImage(), get());
                    tabs.setEnabledAt(2, true);
                    tabs.setSelectedIndex(2);
                    status.setText("Карта зёрен готова.");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    showError(error);
                } catch (ExecutionException error) {
                    showError(error.getCause());
                } finally {
                    setProcessing(false);
                }
            }
        }.execute();
    }

    private void clearResult() {
        tabs.setSelectedIndex(0);
        tabs.setEnabledAt(1, false);
        tabs.setEnabledAt(2, false);
        boundaryPreview.setImage(null);
        grainEditor.setGrains(null, null);
    }

    private void setProcessing(boolean processing) {
        this.processing = processing;
        openImage.setEnabled(!processing);
        chooseModel.setEnabled(!processing);
        analyze.setEnabled(!processing && image != null && modelFile != null);
    }

    private void showError(Throwable error) {
        log.error(error);
        status.setText("Операция не выполнена.");
        String message = error.getMessage();
        JOptionPane.showMessageDialog(this, message == null ? error.toString() : message,
                "Quartz", JOptionPane.ERROR_MESSAGE);
    }
}
