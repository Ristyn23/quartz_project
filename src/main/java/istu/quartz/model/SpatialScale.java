package istu.quartz.model;

import ij.ImagePlus;
import ij.measure.Calibration;
import java.io.*;
import java.util.Properties;

/** Isotropic physical length per source pixel, independent of display zoom. */
public final class SpatialScale {
    public final double pixelSize;
    public final String unit;
    private static final String MARKER = "\n[Quartz scale]\n";

    public SpatialScale(double pixelSize, String unit) {
        if (!Double.isFinite(pixelSize) || pixelSize <= 0 || !Double.isFinite(pixelSize * pixelSize)
                || pixelSize * pixelSize == 0 || unit == null || unit.trim().isEmpty()
                || unit.length() > 40 || unit.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Введите положительное конечное расстояние и единицу измерения.");
        }
        this.pixelSize = pixelSize;
        this.unit = unit.trim();
    }

    public static SpatialScale between(double x1, double y1, double x2, double y2, double distance, String unit) {
        double pixels = Math.hypot(x2 - x1, y2 - y1);
        if (!Double.isFinite(distance) || distance <= 0 || !Double.isFinite(pixels) || pixels <= 0)
            throw new IllegalArgumentException("Точки должны различаться, расстояние должно быть положительным.");
        return new SpatialScale(distance / pixels, unit);
    }

    public void store(Properties properties) {
        properties.setProperty("quartz.scale.pixelSize", Double.toString(pixelSize));
        properties.setProperty("quartz.scale.unit", unit);
    }

    public static SpatialScale read(Properties properties) throws IOException {
        String value = properties.getProperty("quartz.scale.pixelSize");
        if (value == null && !properties.containsKey("quartz.scale.unit")) return null;
        try { return new SpatialScale(Double.parseDouble(value), properties.getProperty("quartz.scale.unit")); }
        catch (RuntimeException error) { throw new IOException("Некорректный масштаб в файле.", error); }
    }

    public static SpatialScale fromImage(ImagePlus image) throws IOException {
        String info = (String) image.getProperty("Info");
        if (info != null && info.contains(MARKER)) {
            Properties properties = new Properties();
            properties.load(new StringReader(info.substring(info.indexOf(MARKER) + MARKER.length())));
            return read(properties);
        }
        Calibration c = image.getCalibration();
        if (!c.scaled() || !Double.isFinite(c.pixelWidth) || c.pixelWidth <= 0
                || Math.abs(c.pixelWidth - c.pixelHeight) > c.pixelWidth * 1e-9) return null;
        try { return new SpatialScale(c.pixelWidth, c.getUnit()); }
        catch (IllegalArgumentException error) { throw new IOException(error); }
    }

    public static void apply(ImagePlus image, SpatialScale scale) {
        Calibration c = image.getCalibration().copy();
        c.pixelWidth = c.pixelHeight = scale == null ? 1 : scale.pixelSize;
        String unit = scale == null ? "pixel" : scale.unit;
        // Standard TIFF calibration uses conventional ASCII unit symbols; our metadata retains the entered label.
        if (unit.equals("мкм")) unit = "um";
        else if (unit.equals("мм")) unit = "mm";
        else if (unit.equals("см")) unit = "cm";
        else if (unit.equals("м")) unit = "m";
        else if (unit.equals("нм")) unit = "nm";
        c.setUnit(unit);
        image.setCalibration(c);
        String info = (String) image.getProperty("Info");
        if (info == null) info = "";
        int marker = info.indexOf(MARKER);
        if (marker >= 0) info = info.substring(0, marker);
        if (scale != null) {
            Properties properties = new Properties();
            scale.store(properties);
            // ImageJ's TIFF Info string can be stored as 8-bit text. Escape Unicode units.
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try { properties.store(bytes, "Spatial scale"); }
            catch (IOException error) { throw new IllegalStateException(error); }
            info += MARKER + new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.ISO_8859_1);
        }
        image.setProperty("Info", info);
    }

    @Override public boolean equals(Object other) {
        if (!(other instanceof SpatialScale)) return false;
        SpatialScale scale = (SpatialScale) other;
        return unit.equals(scale.unit) && Math.abs(pixelSize - scale.pixelSize) <= pixelSize * 1e-12;
    }
    @Override public int hashCode() { return unit.hashCode(); }
}
