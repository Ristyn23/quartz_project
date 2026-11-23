package istu.quartz.model;

import ij.ImagePlus;
import ij.process.ImageProcessor;
import ij.process.FloatProcessor;
import trainableSegmentation.WekaSegmentation;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

public class GrainBoundarySegmenter {

    private static final String BOUNDARY_CLASS = "grain_boundary";
    private static final int TILE_SIZE = 384;

    public ImageProcessor segment(ImagePlus image, File modelFile) throws IOException {
        return segment(image, modelFile, message -> { });
    }

    public ImageProcessor segment(ImagePlus image, File modelFile, Consumer<String> progress) throws IOException {
        if (image.getStackSize() != 1 || image.getType() != ImagePlus.COLOR_RGB) {
            throw new IllegalArgumentException("Для этой модели нужен одиночный RGB-снимок.");
        }

        WekaSegmentation segmentation = new WekaSegmentation(image);
        try {
            try (InputStream modelStream = Files.newInputStream(modelFile.toPath())) {
                if (!segmentation.loadClassifier(modelStream)) {
                    throw new IOException("TWS не смог загрузить модель: " + modelFile.getName());
                }
            }

            int boundaryIndex = Arrays.asList(segmentation.getClassLabels()).indexOf(BOUNDARY_CLASS);
            if (boundaryIndex < 0 || boundaryIndex >= segmentation.getNumOfClasses()) {
                throw new IllegalArgumentException("В выбранной модели нет класса " + BOUNDARY_CLASS + ".");
            }

            if (image.getWidth() > TILE_SIZE || image.getHeight() > TILE_SIZE) {
                return segmentTiles(segmentation, image, boundaryIndex, progress);
            }
            return classify(segmentation, image, boundaryIndex);
        } finally {
            segmentation.shutDownNow();
        }
    }

    private ImageProcessor classify(WekaSegmentation segmentation, ImagePlus image, int boundaryIndex) {
        ImagePlus probabilities = segmentation.applyClassifier(image, 2, true);
        if (probabilities == null || probabilities.getStackSize() <= boundaryIndex) {
            throw new IllegalStateException("TWS не вернул карту вероятности границ.");
        }
        return probabilities.getStack().getProcessor(boundaryIndex + 1);
    }

    private ImageProcessor segmentTiles(WekaSegmentation segmentation, ImagePlus image, int boundaryIndex,
                                        Consumer<String> progress) {
        int width = image.getWidth();
        int height = image.getHeight();
        FloatProcessor boundary = new FloatProcessor(width, height);
        ImageProcessor source = image.getProcessor().duplicate();
        // Include filter context around each tile, then keep only its centre.
        // Eight sigmas also covers cascaded Gaussian/derivative filters; add the membrane half-patch.
        int padding = Math.max(64, (int) Math.ceil(8 * segmentation.getMaximumSigma())
                + segmentation.getMembranePatchSize() / 2 + 2);
        int total = ((width + TILE_SIZE - 1) / TILE_SIZE) * ((height + TILE_SIZE - 1) / TILE_SIZE);
        int tileNumber = 0;
        for (int y = 0; y < height; y += TILE_SIZE) {
            for (int x = 0; x < width; x += TILE_SIZE) {
                if (Thread.currentThread().isInterrupted()) throw new CancellationException();
                progress.accept("TWS: участок " + (++tileNumber) + " из " + total + "…");
                int tileWidth = Math.min(TILE_SIZE, width - x);
                int tileHeight = Math.min(TILE_SIZE, height - y);
                int left = Math.max(0, x - padding);
                int top = Math.max(0, y - padding);
                int right = Math.min(width, x + tileWidth + padding);
                int bottom = Math.min(height, y + tileHeight + padding);
                source.setRoi(left, top, right - left, bottom - top);
                ImagePlus tile = new ImagePlus(image.getTitle() + " — " + tileNumber, source.crop());
                ImageProcessor predicted = classify(segmentation, tile, boundaryIndex);
                for (int row = 0; row < tileHeight; row++) {
                    for (int column = 0; column < tileWidth; column++) {
                        boundary.setf(x + column, y + row, predicted.getf(x - left + column, y - top + row));
                    }
                }
            }
        }
        return boundary;
    }
}
