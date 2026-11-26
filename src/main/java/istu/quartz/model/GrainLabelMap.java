package istu.quartz.model;

import ij.ImagePlus;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;
import inra.ijpb.binary.BinaryImages;
import inra.ijpb.label.LabelImages;

import java.awt.Point;
import java.util.BitSet;
import java.util.List;

public final class GrainLabelMap {

    private static final int MAX_EXACT_FLOAT_LABEL = 1 << 24;

    private final ImagePlus image;
    private final float[] labels;
    private int lastLabel;
    private BitSet allowed;
    public void restrictTo(ByteProcessor mask) {
        if (mask == null) return;
        if (mask.getWidth() != getWidth() || mask.getHeight() != getHeight()) throw new IllegalArgumentException("Размер маски области.");
        allowed = new BitSet(labels.length);
        for (int i = 0; i < labels.length; i++) {
            if (mask.get(i) != 0) allowed.set(i); else labels[i] = 0;
        }
    }

    public GrainLabelMap(ImageProcessor processor) {
        image = new ImagePlus("Quartz — карта зёрен", processor.convertToFloatProcessor());
        labels = (float[]) image.getProcessor().getPixels();
        for (float label : labels) {
            lastLabel = Math.max(lastLabel, (int) label);
        }
    }

    public int getWidth() {
        return image.getWidth();
    }

    public int getHeight() {
        return image.getHeight();
    }

    public int labelAt(int x, int y) {
        if (x < 0 || x >= getWidth() || y < 0 || y >= getHeight()) {
            return 0;
        }
        return (int) labels[y * getWidth() + x];
    }

    public int areaInPixels(int label) {
        int area = 0;
        if (label > 0) {
            for (float pixelLabel : labels) {
                if (pixelLabel == label) {
                    area++;
                }
            }
        }
        return area;
    }

    public GrainMeasurement measure(int label) {
        ImageProcessor grain = LabelImages.cropLabel(image.getProcessor(), label, 1);
        // MorphoLibJ crops the selected ID as a binary region with foreground value 255.
        return GrainMeasurement.measure(grain, new int[]{255})[0];
    }

    public ImageProcessor snapshot() {
        return image.getProcessor().duplicate();
    }

    public boolean merge(int first, int second) {
        if (first <= 0 || second <= 0 || first == second) {
            return false;
        }

        BitSet sharedBoundary = new BitSet(labels.length);
        boolean touching = false;
        for (int i = 0; i < labels.length; i++) {
            if (labels[i] == first && hasNeighbor(i, second)) {
                touching = true;
            }
            if ((allowed == null || allowed.get(i)) && labels[i] == 0 && hasNeighbor(i, first) && hasNeighbor(i, second)) {
                sharedBoundary.set(i);
            }
        }

        if (!touching && sharedBoundary.isEmpty()) {
            return false;
        }
        for (int i = 0; i < labels.length; i++) {
            if (labels[i] == second || sharedBoundary.get(i)) {
                labels[i] = first;
            }
        }
        return true;
    }

    private boolean hasNeighbor(int index, int label) {
        int width = getWidth();
        int x = index % width;
        return (x > 0 && labels[index - 1] == label)
                || (x < width - 1 && labels[index + 1] == label)
                || (index >= width && labels[index - width] == label)
                || (index < labels.length - width && labels[index + width] == label);
    }

    public int split(int grain, List<Point> stroke) {
        if (grain <= 0 || stroke.size() < 2 || lastLabel >= MAX_EXACT_FLOAT_LABEL) {
            return 0;
        }

        ByteProcessor mask = new ByteProcessor(getWidth(), getHeight());
        byte[] maskPixels = (byte[]) mask.getPixels();
        for (int i = 0; i < labels.length; i++) {
            if (labels[i] == grain) {
                maskPixels[i] = (byte) 255;
            }
        }
        mask.setValue(0);
        Point previous = stroke.get(0);
        for (int i = 1; i < stroke.size(); i++) {
            Point current = stroke.get(i);
            mask.drawLine(previous.x, previous.y, current.x, current.y);
            previous = current;
        }

        ImageProcessor components = BinaryImages.componentsLabeling(mask, 4, 32);
        int firstSize = 0;
        int secondSize = 0;
        for (int i = 0; i < labels.length; i++) {
            int component = (int) components.getf(i);
            if (component > 2) {
                return 0;
            }
            if (component == 1) {
                firstSize++;
            } else if (component == 2) {
                secondSize++;
            }
        }
        if (firstSize == 0 || secondSize == 0) {
            return 0;
        }

        int keptComponent = firstSize >= secondSize ? 1 : 2;
        int newLabel = ++lastLabel;
        for (int i = 0; i < labels.length; i++) {
            if (labels[i] == grain) {
                int component = (int) components.getf(i);
                if (component == 0) {
                    labels[i] = 0;
                } else if (component != keptComponent) {
                    labels[i] = newLabel;
                }
            }
        }
        return newLabel;
    }
}
