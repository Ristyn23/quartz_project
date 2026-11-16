package istu.quartz.model;

import ij.process.ImageProcessor;
import inra.ijpb.label.LabelImages;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class GrainCsvExporter {

    private GrainCsvExporter() {
    }

    public static void write(ImageProcessor labels, Path destination, boolean replaceExisting) throws IOException {
        int[] grainIds = LabelImages.findAllLabels(labels);
        GrainMeasurement[] measurements = GrainMeasurement.measure(labels, grainIds);

        Path output = destination.toAbsolutePath();
        Path temporary = Files.createTempFile(output.getParent(), ".quartz-measurements-", ".csv");
        try {
            try (BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                writer.write("grain_id,area_px2,perimeter_px,equivalent_diameter_px,major_axis_px,minor_axis_px,"
                        + "aspect_ratio,circularity,roundness");
                writer.newLine();
                for (int i = 0; i < grainIds.length; i++) {
                    GrainMeasurement grain = measurements[i];
                    writer.write(String.join(",", Integer.toString(grainIds[i]), Integer.toString(grain.areaPx2),
                            number(grain.perimeterPx), number(grain.equivalentDiameterPx),
                            number(grain.majorAxisPx), number(grain.minorAxisPx), number(grain.aspectRatio),
                            number(grain.circularity), number(grain.roundness)));
                    writer.newLine();
                }
            }
            if (replaceExisting) {
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

    private static String number(double value) {
        return Double.isFinite(value) ? Double.toString(value) : "";
    }
}
