package istu.quartz.model;

import ij.process.ByteProcessor;
import ij.process.ImageProcessor;
import inra.ijpb.watershed.ExtendedMinimaWatershed;

public final class GrainWatershed {

    private static final int SIGMA_PIXELS = 4;
    private static final int TOLERANCE = 20;
    private static final int CONNECTIVITY = 4;

    public GrainLabelMap label(ImageProcessor probability) {
        return label(probability, null);
    }

    public GrainLabelMap label(ImageProcessor probability, ByteProcessor region) {
        int width = probability.getWidth();
        int height = probability.getHeight();
        float[] values = (float[]) probability.convertToFloatProcessor().getPixels();
        if (region != null) {
            if (region.getWidth() != width || region.getHeight() != height) throw new IllegalArgumentException("Размер маски области.");
            values = values.clone();
            for (int i = 0; i < values.length; i++) if (region.get(i) == 0) values[i] = 1;
        }
        byte[] relief = smoothAndQuantize(values, width, height);
        ImageProcessor labels = ExtendedMinimaWatershed.extendedMinimaWatershed(
                new ByteProcessor(width, height, relief), region, TOLERANCE, CONNECTIVITY, true);
        if (labels == null) {
            throw new IllegalStateException("Watershed не вернул карту зёрен.");
        }
        GrainLabelMap result = new GrainLabelMap(labels);
        result.restrictTo(region);
        return result;
    }

    private byte[] smoothAndQuantize(float[] values, int width, int height) {
        // Match the pilot's separable Gaussian filter and nearest-pixel edge padding.
        int radius = 4 * SIGMA_PIXELS;
        double[] kernel = new double[2 * radius + 1];
        double total = 0;
        for (int offset = -radius; offset <= radius; offset++) {
            double distance = (double) offset / SIGMA_PIXELS;
            kernel[offset + radius] = Math.exp(-0.5 * distance * distance);
            total += kernel[offset + radius];
        }
        for (int i = 0; i < kernel.length; i++) {
            kernel[i] /= total;
        }

        float[] vertical = new float[values.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double sum = 0;
                for (int offset = -radius; offset <= radius; offset++) {
                    int row = Math.max(0, Math.min(height - 1, y + offset));
                    sum += values[row * width + x] * kernel[offset + radius];
                }
                vertical[y * width + x] = (float) sum;
            }
        }

        byte[] relief = new byte[values.length];
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                double sum = 0;
                for (int offset = -radius; offset <= radius; offset++) {
                    int column = Math.max(0, Math.min(width - 1, x + offset));
                    sum += vertical[row + column] * kernel[offset + radius];
                }
                float filtered = (float) sum;
                relief[row + x] = (byte) Math.round(255.0 * Math.max(0, Math.min(1, filtered)));
            }
        }
        return relief;
    }
}
