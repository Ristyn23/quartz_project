package istu.quartz.model;

import ij.ImagePlus;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;

public final class SelectionImages {
    private SelectionImages() { }

    public static ImagePlus extract(ImagePlus source, Shape shape, Rectangle rectangle, double angle) throws IOException {
        if (shape == null) return source.duplicate();
        boolean rotated = rectangle != null && angle != 0;
        Rectangle bounds = rotated ? new Rectangle(0, 0, rectangle.width, rectangle.height)
                : shape.getBounds().intersection(new Rectangle(0, 0, source.getWidth(), source.getHeight()));
        if (bounds.isEmpty()) throw new IOException("Выделенная область находится за пределами снимка.");
        AffineTransform toSource = rotated
                ? AffineTransform.getTranslateInstance(rectangle.getCenterX(), rectangle.getCenterY())
                : AffineTransform.getTranslateInstance(bounds.x, bounds.y);
        if (rotated) { toSource.rotate(angle); toSource.translate(-rectangle.width / 2.0, -rectangle.height / 2.0); }
        BufferedImage pixels = new BufferedImage(bounds.width, bounds.height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = pixels.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(source.getBufferedImage(), toSource.createInverse(), null);
        } catch (java.awt.geom.NoninvertibleTransformException error) { throw new IOException(error); }
        finally { g.dispose(); }
        ByteProcessor previous = AnalysisRegion.read(source);
        ByteProcessor mask = new ByteProcessor(bounds.width, bounds.height);
        boolean any = false, all = true;
        double[] point = new double[2];
        for (int y = 0; y < bounds.height; y++) for (int x = 0; x < bounds.width; x++) {
            point[0] = x + .5; point[1] = y + .5; toSource.transform(point, 0, point, 0, 1);
            int xx = (int) Math.floor(point[0]), yy = (int) Math.floor(point[1]);
            boolean valid = xx >= 0 && yy >= 0 && xx < source.getWidth() && yy < source.getHeight()
                    && shape.contains(point[0], point[1]) && (previous == null || previous.get(xx, yy) != 0);
            mask.set(x, y, valid ? 255 : 0); any |= valid; all &= valid;
            if (!valid) pixels.setRGB(x, y, 0);
        }
        if (!any) throw new IOException("Выделенная область пуста.");
        ImagePlus output = new ImagePlus(source.getTitle() + " — фрагмент", new ColorProcessor(pixels));
        output.setCalibration(source.getCalibration().copy());
        if (rotated) { output.getCalibration().xOrigin = 0; output.getCalibration().yOrigin = 0; }
        else { output.getCalibration().xOrigin -= bounds.x; output.getCalibration().yOrigin -= bounds.y; }
        output.setProperty("Info", "Quartz selection; rotation=" + Math.toDegrees(angle) + "\n" + (source.getInfoProperty() == null ? "" : source.getInfoProperty()));
        AnalysisRegion.attach(output, all ? null : mask);
        SpatialScale.apply(output, SpatialScale.fromImage(source));
        return output;
    }
}
