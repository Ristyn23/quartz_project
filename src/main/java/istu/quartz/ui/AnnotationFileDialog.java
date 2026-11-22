package istu.quartz.ui;

import ij.process.ImageProcessor;
import istu.quartz.model.GrainAnnotationIO;
import istu.quartz.model.GrainAnnotationIO.Format;

import javax.swing.ButtonGroup;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JRadioButton;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.GridLayout;
import java.awt.Dialog.ModalityType;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.ExecutionException;

final class AnnotationFileDialog {

    final File file;
    final Format format;
    final boolean replace;

    private AnnotationFileDialog(File file, Format format, boolean replace) {
        this.file = file;
        this.format = format;
        this.replace = replace;
    }

    static AnnotationFileDialog choose(Component owner, boolean save) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(save ? "Сохранить разметку" : "Открыть разметку");
        chooser.setAcceptAllFileFilterUsed(false);
        FileNameExtensionFilter tiff = new FileNameExtensionFilter("TIFF с картой меток (*.tif, *.tiff)", "tif", "tiff");
        FileNameExtensionFilter zip = new FileNameExtensionFilter("ROI ZIP для Fiji (*.zip)", "zip");
        chooser.setFileFilter(tiff);
        JRadioButton tiffChoice = new JRadioButton("TIFF с картой меток", true);
        JRadioButton roiChoice = new JRadioButton("ROI ZIP для Fiji");
        ButtonGroup group = new ButtonGroup();
        group.add(tiffChoice);
        group.add(roiChoice);
        JPanel formats = new JPanel(new GridLayout(0, 1, 0, 8));
        formats.add(new JLabel("Формат разметки"));
        formats.add(tiffChoice);
        formats.add(roiChoice);
        chooser.setAccessory(formats);
        chooser.addPropertyChangeListener(JFileChooser.FILE_FILTER_CHANGED_PROPERTY, event -> {
            tiffChoice.setSelected(chooser.getFileFilter() == tiff);
            roiChoice.setSelected(chooser.getFileFilter() == zip);
        });
        tiffChoice.addActionListener(event -> {
            chooser.setFileFilter(tiff);
            if (save) {
                chooser.setSelectedFile(new File("labels.tif"));
            }
        });
        roiChoice.addActionListener(event -> {
            chooser.setFileFilter(zip);
            if (save) {
                chooser.setSelectedFile(new File("grains.zip"));
            }
        });
        if (save) {
            chooser.setSelectedFile(new File("labels.tif"));
        }
        int result = save ? chooser.showSaveDialog(owner) : chooser.showOpenDialog(owner);
        if (result != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        Format format = tiffChoice.isSelected() ? Format.TIFF : Format.ROI_ZIP;
        File file = chooser.getSelectedFile();
        FileNameExtensionFilter filter = format == Format.TIFF ? tiff : zip;
        if (!filter.accept(file) && save) {
            file = new File(file.getParentFile(), file.getName() + (format == Format.TIFF ? ".tif" : ".zip"));
        }
        if (file.isDirectory() || !filter.accept(file)) {
            JOptionPane.showMessageDialog(owner, "Выберите файл в указанном формате.", "Разметка", JOptionPane.ERROR_MESSAGE);
            return null;
        }
        boolean replace = save && Files.exists(file.toPath());
        if (replace && JOptionPane.showConfirmDialog(owner, "Заменить " + file.getName() + "?",
                "Сохранить разметку", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
            return null;
        }
        return new AnnotationFileDialog(file, format, replace);
    }

    static boolean save(Component owner, ImageProcessor labels) {
        AnnotationFileDialog target = choose(owner, true);
        if (target == null) {
            return false;
        }
        JDialog progress = new JDialog(SwingUtilities.getWindowAncestor(owner), "Сохранение разметки",
                ModalityType.APPLICATION_MODAL);
        JProgressBar bar = new JProgressBar();
        bar.setIndeterminate(true);
        JPanel content = new JPanel(new BorderLayout(10, 10));
        content.setBorder(javax.swing.BorderFactory.createEmptyBorder(15, 15, 15, 15));
        content.add(new JLabel("Сохраняется " + target.file.getName()), BorderLayout.PAGE_START);
        content.add(bar, BorderLayout.CENTER);
        progress.setContentPane(content);
        progress.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        progress.pack();
        progress.setLocationRelativeTo(owner);
        SwingWorker<Void, Void> worker = new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                GrainAnnotationIO.write(labels, target.file.toPath(), target.format, target.replace);
                return null;
            }

            @Override
            protected void done() {
                progress.dispose();
            }
        };
        worker.execute();
        progress.setVisible(true);
        try {
            worker.get();
            return true;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            JOptionPane.showMessageDialog(owner, error.toString(), "Сохранение разметки", JOptionPane.ERROR_MESSAGE);
        } catch (ExecutionException error) {
            JOptionPane.showMessageDialog(owner, error.getCause().toString(), "Сохранение разметки", JOptionPane.ERROR_MESSAGE);
        }
        return false;
    }
}
