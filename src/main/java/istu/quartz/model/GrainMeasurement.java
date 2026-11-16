package istu.quartz.model;

import ij.measure.Calibration;
import ij.process.ImageProcessor;
import inra.ijpb.geometry.Ellipse;
import inra.ijpb.measure.IntrinsicVolumes2D;
import inra.ijpb.measure.region2d.EquivalentEllipse;

public final class GrainMeasurement {

    private static final int CROFTON_DIRECTIONS = 4;

    public final int areaPx2;
    public final double perimeterPx;
    public final double equivalentDiameterPx;
    public final double majorAxisPx;
    public final double minorAxisPx;
    public final double aspectRatio;
    public final double circularity;
    public final double roundness;

    private GrainMeasurement(int area, double perimeter, Ellipse ellipse) {
        areaPx2 = area;
        perimeterPx = perimeter;
        equivalentDiameterPx = 2 * Math.sqrt(areaPx2 / Math.PI);
        majorAxisPx = 2 * Math.max(ellipse.radius1(), ellipse.radius2());
        minorAxisPx = 2 * Math.min(ellipse.radius1(), ellipse.radius2());
        aspectRatio = minorAxisPx > 0 ? majorAxisPx / minorAxisPx : Double.NaN;
        circularity = unitRatio(4 * Math.PI * areaPx2, perimeterPx * perimeterPx);
        roundness = unitRatio(4 * areaPx2, Math.PI * majorAxisPx * majorAxisPx);
    }

    static GrainMeasurement[] measure(ImageProcessor image, int[] labels) {
        if (labels.length == 0) {
            return new GrainMeasurement[0];
        }
        Calibration pixels = new Calibration();
        double[] areas = IntrinsicVolumes2D.areas(image, labels, pixels);
        double[] perimeters = IntrinsicVolumes2D.perimeters(image, labels, pixels, CROFTON_DIRECTIONS);
        Ellipse[] ellipses = EquivalentEllipse.equivalentEllipses(image, labels, pixels);

        GrainMeasurement[] measurements = new GrainMeasurement[labels.length];
        for (int i = 0; i < labels.length; i++) {
            measurements[i] = new GrainMeasurement((int) areas[i], perimeters[i], ellipses[i]);
        }
        return measurements;
    }

    private static double unitRatio(double numerator, double denominator) {
        // Pixel sampling can put the estimate slightly above the continuous maximum.
        return denominator > 0 ? Math.min(1, numerator / denominator) : Double.NaN;
    }
}
