package istu.quartz.model;

import ij.ImagePlus;
import ij.process.ImageProcessor;
import trainableSegmentation.WekaSegmentation;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Arrays;

public class GrainBoundarySegmenter {

    private static final String BOUNDARY_CLASS = "grain_boundary";

    public ImageProcessor segment(ImagePlus image, File modelFile) throws IOException {
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

            ImagePlus probabilities = segmentation.applyClassifier(image, 2, true);
            if (probabilities == null || probabilities.getStackSize() <= boundaryIndex) {
                throw new IllegalStateException("TWS не вернул карту вероятности границ.");
            }
            return probabilities.getStack().getProcessor(boundaryIndex + 1);
        } finally {
            segmentation.shutDownNow();
        }
    }
}
