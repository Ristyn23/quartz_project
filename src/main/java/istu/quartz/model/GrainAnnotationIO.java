package istu.quartz.model;

import ij.ImagePlus;
import ij.gui.Roi;
import ij.io.FileSaver;
import ij.io.Opener;
import ij.io.RoiDecoder;
import ij.io.RoiEncoder;
import ij.measure.Calibration;
import ij.plugin.filter.ThresholdToSelection;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import inra.ijpb.label.LabelImages;
import inra.ijpb.geometry.Box2D;
import inra.ijpb.measure.region2d.BoundingBox;

import java.awt.Rectangle;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public final class GrainAnnotationIO {

    public enum Format {
        TIFF, ROI_ZIP
    }

    private GrainAnnotationIO() {
    }

    public static GrainLabelMap read(Path file, Format format, int width, int height) throws IOException {
        return readDocument(file, format, width, height).labels;
    }

    public static final class Document {
        public final GrainLabelMap labels;
        public final SpatialScale scale;
        Document(GrainLabelMap labels, SpatialScale scale) { this.labels = labels; this.scale = scale; }
    }

    public static Document readDocument(Path file, Format format, int width, int height) throws IOException {
        ImageProcessor labels;
        SpatialScale scale = null;
        if (format == Format.TIFF) {
            Opener opener = new Opener();
            opener.setSilentMode(true);
            ImagePlus opened = opener.openImage(file.toAbsolutePath().toString());
            if (opened == null || opened.getStackSize() != 1 || opened.getType() == ImagePlus.COLOR_RGB) {
                throw new IOException("Выберите одиночную TIFF-карту с числовыми метками.");
            }
            labels = opened.getProcessor();
            scale = SpatialScale.fromImage(opened);
        } else {
            Properties metadata = new Properties();
            labels = readRois(file, width, height, metadata);
            scale = SpatialScale.read(metadata);
        }
        validate(labels, width, height);
        return new Document(new GrainLabelMap(labels), scale);
    }

    private static void validate(ImageProcessor labels, int width, int height) throws IOException {
        if (labels.getWidth() != width || labels.getHeight() != height) {
            throw new IOException("Размер разметки не совпадает с размером снимка.");
        }
        boolean hasGrain = false;
        for (int i = 0; i < labels.getPixelCount(); i++) {
            float value = labels.getf(i);
            if (!Float.isFinite(value) || value < 0 || value > (1 << 24) || value != Math.rint(value)) {
                throw new IOException("Метки должны быть целыми числами от 0 до 16777216.");
            }
            hasGrain |= value > 0;
        }
        if (!hasGrain) {
            throw new IOException("В разметке нет зёрен.");
        }
    }

    public static void write(ImageProcessor labels, Path file, Format format, boolean replace) throws IOException {
        write(labels, file, format, replace, null);
    }

    public static void write(ImageProcessor labels, Path file, Format format, boolean replace, SpatialScale scale) throws IOException {
        validate(labels, labels.getWidth(), labels.getHeight());
        Path output = file.toAbsolutePath();
        Path temporary = Files.createTempFile(output.getParent(), ".quartz-labels-", ".tmp");
        try {
            if (format == Format.TIFF) {
                ImagePlus outputImage = new ImagePlus("Quartz labels", labels);
                SpatialScale.apply(outputImage, scale);
                if (!new FileSaver(outputImage).saveAsTiff(temporary.toString())) {
                    throw new IOException("Не удалось записать TIFF.");
                }
            } else {
                writeRois(labels, temporary, scale);
            }
            if (replace) {
                try {
                    Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException error) {
                    Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
                }
            } else {
                Files.move(temporary, output);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void writeRois(ImageProcessor labels, Path file, SpatialScale scale) throws IOException {
        try (OutputStream stream = Files.newOutputStream(file);
             ZipOutputStream zip = new ZipOutputStream(stream)) {
            Properties canvas = new Properties();
            canvas.setProperty("width", Integer.toString(labels.getWidth()));
            canvas.setProperty("height", Integer.toString(labels.getHeight()));
            if (scale != null) scale.store(canvas);
            zip.putNextEntry(new ZipEntry("quartz.properties"));
            canvas.store(zip, "Quartz image dimensions");
            zip.closeEntry();
            int[] ids = LabelImages.findAllLabels(labels);
            Box2D[] boxes = BoundingBox.boundingBoxes(labels, ids, new Calibration());
            for (int i = 0; i < ids.length; i++) {
                int id = ids[i];
                Box2D box = boxes[i];
                int left = (int) box.getXMin();
                int top = (int) box.getYMin();
                ImageProcessor mask = new ByteProcessor((int) box.width(), (int) box.height());
                for (int y = 0; y < mask.getHeight(); y++) {
                    for (int x = 0; x < mask.getWidth(); x++) {
                        if (labels.getf(left + x, top + y) == id) {
                            mask.set(x, y, 255);
                        }
                    }
                }
                mask.setThreshold(255, 255, ImageProcessor.NO_LUT_UPDATE);
                Roi roi = new ThresholdToSelection().convert(mask);
                roi.setLocation(left, top);
                roi.setName("grain_" + id);
                zip.putNextEntry(new ZipEntry("grain_" + id + ".roi"));
                zip.write(RoiEncoder.saveAsByteArray(roi));
                zip.closeEntry();
            }
        }
    }

    private static ImageProcessor readRois(Path file, int width, int height, Properties canvas) throws IOException {
        FloatProcessor labels = new FloatProcessor(width, height);
        Set<Integer> usedIds = new HashSet<>();
        int nextId = 1;
        try (ZipFile zip = new ZipFile(file.toFile())) {
            ZipEntry metadata = zip.getEntry("quartz.properties");
            if (metadata != null) {

                try (InputStream stream = zip.getInputStream(metadata)) {
                    canvas.load(stream);
                }
                if (!Integer.toString(width).equals(canvas.getProperty("width"))
                        || !Integer.toString(height).equals(canvas.getProperty("height"))) {
                    throw new IOException("Размер ROI-разметки не совпадает с размером снимка.");
                }
            }
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().toLowerCase(Locale.ROOT).endsWith(".roi")) {
                    continue;
                }
                Roi roi;
                try (InputStream stream = zip.getInputStream(entry);
                     ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = stream.read(buffer)) != -1) {
                        bytes.write(buffer, 0, count);
                    }
                    roi = new RoiDecoder(bytes.toByteArray(), entry.getName()).getRoi();
                }
                if (roi == null || !roi.isArea()) {
                    throw new IOException("ROI должен задавать область зерна: " + entry.getName());
                }
                Rectangle bounds = roi.getBounds();
                if (bounds.isEmpty() || bounds.x < 0 || bounds.y < 0
                        || (long) bounds.x + bounds.width > width || (long) bounds.y + bounds.height > height) {
                    throw new IOException("ROI выходит за пределы снимка: " + entry.getName());
                }
                String name = roi.getName();
                int id;
                if (name != null && name.matches("grain_[0-9]{1,8}")) {
                    id = Integer.parseInt(name.substring(6));
                } else {
                    while (usedIds.contains(nextId)) {
                        nextId++;
                    }
                    id = nextId++;
                }
                if (id <= 0 || id > (1 << 24) || !usedIds.add(id)) {
                    throw new IOException("Недопустимый или повторяющийся ID зерна: " + id);
                }
                ImageProcessor mask = roi.getMask();
                for (int y = 0; y < bounds.height; y++) {
                    for (int x = 0; x < bounds.width; x++) {
                        if (mask == null || mask.get(x, y) != 0) {
                            int px = bounds.x + x;
                            int py = bounds.y + y;
                            if (labels.getf(px, py) != 0) {
                                throw new IOException("Области ROI пересекаются: " + entry.getName());
                            }
                            labels.setf(px, py, id);
                        }
                    }
                }
            }
        }
        return labels;
    }
}
