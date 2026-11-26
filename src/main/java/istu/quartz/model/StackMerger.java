package istu.quartz.model;

import ij.ImagePlus;
import ij.ImageStack;
import ij.plugin.filter.GaussianBlur;
import ij.process.ColorProcessor;
import ij.process.ImageProcessor;
import mpicbg.ij.FeatureTransform;
import mpicbg.ij.InverseTransformMapping;
import mpicbg.ij.SIFT;
import mpicbg.imagefeatures.Feature;
import mpicbg.imagefeatures.FloatArray2DSIFT;
import mpicbg.models.PointMatch;
import mpicbg.models.RigidModel2D;

import java.awt.Rectangle;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

/** Rigid SIFT registration (mpicbg), followed by a mean over actual image coverage. */
public final class StackMerger {
    private static final int FEATURE_SIZE = 1024;

    public static final class Alignment {
        private final ImageStack frames;
        private final Rectangle bounds;
        private final byte[][] coverage;
        private final List<String> descriptions;
        private final SpatialScale scale;

        private Alignment(ImageStack frames, Rectangle bounds, byte[][] coverage, List<String> descriptions, SpatialScale scale) {
            this.frames = frames;
            this.bounds = new Rectangle(bounds);
            this.coverage = coverage;
            this.descriptions = descriptions;
            this.scale = scale;
        }

        public int size() { return frames.getSize(); }
        public ImageProcessor frame(int index) { return frames.getProcessor(index + 1); }
        public Rectangle bounds() { return new Rectangle(bounds); }
        public String description(int index) { return descriptions.get(index); }

        public ImagePlus average() {
            checkInterrupted();
            int count = frames.getSize();
            int[][] pixels = new int[count][];
            for (int i = 0; i < count; i++) {
                pixels[i] = (int[]) frames.getProcessor(i + 1).getPixels();
            }
            int[] merged = new int[bounds.width * bounds.height];
            for (int p = 0; p < merged.length; p++) {
                if (p % bounds.width == 0) checkInterrupted();
                long red = 0, green = 0, blue = 0;
                int contributors = 0;
                for (int i = 0; i < count; i++) {
                    // A real black pixel must contribute too. Pixel colour is not a validity mask.
                    if (coverage[i][p] == 0) continue;
                    int rgb = pixels[i][p];
                    red += (rgb >> 16) & 255;
                    green += (rgb >> 8) & 255;
                    blue += rgb & 255;
                    contributors++;
                }
                if (contributors != 0) {
                    merged[p] = ((int) ((red + contributors / 2) / contributors) << 16)
                            | ((int) ((green + contributors / 2) / contributors) << 8)
                            | (int) ((blue + contributors / 2) / contributors);
                }
            }
            ImagePlus output = new ImagePlus("Quartz merged", new ColorProcessor(bounds.width, bounds.height, merged));
            output.setProperty("Info", "Quartz: SIFT rigid alignment; coverage-aware RGB mean.\n"
                    + "Full canvas in reference frame: " + bounds + "\n" + String.join("\n", descriptions));
            SpatialScale.apply(output, scale);
            return output;
        }
    }

    public Alignment align(List<ImagePlus> inputs, Consumer<String> progress) throws Exception {
        if (inputs.size() < 2) {
            throw new IllegalArgumentException("Добавьте хотя бы два снимка.");
        }
        int width = inputs.get(0).getWidth();
        int height = inputs.get(0).getHeight();
        for (ImagePlus input : inputs) {
            if (input.getStackSize() != 1 || input.getType() != ImagePlus.COLOR_RGB
                    || input.getWidth() != width || input.getHeight() != height) {
                throw new IllegalArgumentException("Нужны одиночные RGB-снимки одинакового размера.");
            }
        }
        double scale = Math.min(1.0, FEATURE_SIZE / (double) Math.max(width, height));
        int smallWidth = Math.max(1, (int) Math.round(width * scale));
        int smallHeight = Math.max(1, (int) Math.round(height * scale));
        // Scale coordinates back separately because resizing rounds each image dimension.
        double sx = smallWidth / (double) width;
        double sy = smallHeight / (double) height;
        List<RigidModel2D> transforms = new ArrayList<>();
        List<String> descriptions = new ArrayList<>();
        RigidModel2D cumulative = new RigidModel2D();
        transforms.add(cumulative.copy());
        descriptions.add("1: опорный кадр — " + inputs.get(0).getTitle());
        List<Feature> previous = features(inputs.get(0), smallWidth, smallHeight);
        for (int i = 1; i < inputs.size(); i++) {
            checkInterrupted();
            progress.accept("Совмещение кадра " + (i + 1) + " из " + inputs.size() + "…");
            List<Feature> current = features(inputs.get(i), smallWidth, smallHeight);
            List<PointMatch> matches = new ArrayList<>();
            FeatureTransform.matchFeatures(current, previous, matches, 0.92f);
            // Fit in original pixel coordinates so rotation never introduces anisotropic scaling.
            List<PointMatch> scaledMatches = new ArrayList<>();
            for (PointMatch match : matches) {
                double[] a = match.getP1().getL();
                double[] b = match.getP2().getL();
                scaledMatches.add(new PointMatch(new mpicbg.models.Point(new double[]{a[0] / sx, a[1] / sy}),
                        new mpicbg.models.Point(new double[]{b[0] / sx, b[1] / sy})));
            }
            RigidModel2D step = new RigidModel2D();
            List<PointMatch> inliers = new ArrayList<>();
            if (scaledMatches.size() < 8 || !step.filterRansac(scaledMatches, inliers, 2000, 3.0 / scale, 0.1, 8)) {
                throw new IOException("Не удалось надёжно совместить кадр «" + inputs.get(i).getTitle()
                        + "». Проверьте порядок кадров и что на них одна область образца.");
            }
            // All frames are mapped to the first frame; each source is resampled only once.
            cumulative.concatenate(step);
            transforms.add(cumulative.copy());
            double[] matrix = new double[6];
            cumulative.toArray(matrix);
            descriptions.add(String.format(java.util.Locale.ROOT,
                    "%d: %s · поворот %.2f° · сдвиг (%.1f; %.1f) px · совпадений %d",
                    i + 1, inputs.get(i).getTitle(), Math.toDegrees(Math.atan2(matrix[1], matrix[0])),
                    matrix[4], matrix[5], inliers.size()));
            previous = current;
        }
        return render(inputs, transforms, descriptions, progress);
    }

    static Alignment render(List<ImagePlus> inputs, List<RigidModel2D> transforms,
                            List<String> descriptions, Consumer<String> progress) throws IOException {
        progress.accept("Подготовка всей области кадров…");
        int width = inputs.get(0).getWidth();
        int height = inputs.get(0).getHeight();
        Rectangle bounds = unionBounds(transforms, width, height);
        ImageStack aligned = new ImageStack(bounds.width, bounds.height);
        byte[][] coverage = new byte[inputs.size()][];
        for (int i = 0; i < inputs.size(); i++) {
            checkInterrupted();
            progress.accept("Подготовка совмещённого кадра " + (i + 1) + "…");
            ColorProcessor target = new ColorProcessor(bounds.width, bounds.height);
            // mpicbg's bilinear mapping omits the source's final row and column.
            // Replicate a one-pixel border so original edge pixels remain sampleable.
            ImageProcessor original = inputs.get(i).getProcessor();
            ColorProcessor source = new ColorProcessor(width + 1, height + 1);
            source.insert(original, 0, 0);
            for (int y = 0; y < height; y++) source.set(width, y, original.get(width - 1, y));
            for (int x = 0; x <= width; x++) source.set(x, height, original.get(Math.min(x, width - 1), height - 1));
            source.setInterpolationMethod(ImageProcessor.BILINEAR);
            double[] matrix = new double[6];
            transforms.get(i).toArray(matrix);
            RigidModel2D onCanvas = new RigidModel2D();
            onCanvas.set(matrix[0], matrix[1], matrix[4] - bounds.x, matrix[5] - bounds.y);
            new InverseTransformMapping<>(onCanvas).mapInterpolated(source, target);
            onCanvas.createInverse().toArray(matrix);
            byte[] valid = new byte[bounds.width * bounds.height];
            for (int y = 0; y < bounds.height; y++) {
                checkInterrupted();
                for (int x = 0; x < bounds.width; x++) {
                    double px = matrix[0] * x + matrix[2] * y + matrix[4];
                    double py = matrix[1] * x + matrix[3] * y + matrix[5];
                    if (px >= 0 && py >= 0 && px <= width - 1 && py <= height - 1) {
                        valid[y * bounds.width + x] = 1;
                    } else {
                        target.set(x, y, 0);
                    }
                }
            }
            coverage[i] = valid;
            aligned.addSlice(inputs.get(i).getTitle(), target);
        }
        return new Alignment(aligned, bounds, coverage, descriptions, SpatialScale.fromImage(inputs.get(0)));
    }

    private List<Feature> features(ImagePlus image, int width, int height) {
        checkInterrupted();
        ImageProcessor gray = image.getProcessor().convertToFloatProcessor();
        gray.setInterpolationMethod(ImageProcessor.BILINEAR);
        gray = gray.resize(width, height);
        // Gradient magnitude preserves boundaries when grains exchange brightness.
        new GaussianBlur().blurGaussian(gray, 1);
        gray.findEdges();
        FloatArray2DSIFT.Param param = new FloatArray2DSIFT.Param();
        param.maxOctaveSize = FEATURE_SIZE;
        param.minOctaveSize = 32;
        List<Feature> features = new ArrayList<>();
        new SIFT(new FloatArray2DSIFT(param)).extractFeatures(gray, features);
        return features;
    }

    /** Full bounding canvas of all transformed source pixel centres, including negative coordinates. */
    private static Rectangle unionBounds(List<RigidModel2D> transforms, int width, int height) throws IOException {
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (RigidModel2D model : transforms) {
            for (int y : new int[]{0, height - 1}) {
                for (int x : new int[]{0, width - 1}) {
                    double[] point = model.apply(new double[]{x, y});
                    minX = Math.min(minX, point[0]);
                    minY = Math.min(minY, point[1]);
                    maxX = Math.max(maxX, point[0]);
                    maxY = Math.max(maxY, point[1]);
                }
            }
        }
        double left = Math.floor(minX), top = Math.floor(minY);
        double w = Math.ceil(maxX) - left + 1, h = Math.ceil(maxY) - top + 1;
        if (!Double.isFinite(w) || !Double.isFinite(h) || left < Integer.MIN_VALUE || top < Integer.MIN_VALUE
                || left > Integer.MAX_VALUE || top > Integer.MAX_VALUE || w < 1 || h < 1
                || w * h > Integer.MAX_VALUE - 8) {
            throw new IOException("Размер объединённого снимка слишком велик. Проверьте совмещение.");
        }
        return new Rectangle((int) left, (int) top, (int) w, (int) h);
    }

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException();
        }
    }
}
