package istu.quartz.model;

import ij.ImagePlus;
import ij.process.ByteProcessor;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Base64;
import javax.imageio.ImageIO;

/** Lossless analysis mask embedded in TIFF Info, shared by file import and direct handoff. */
public final class AnalysisRegion {
    private static final String KEY = "QuartzAnalysisMask=";
    private AnalysisRegion() { }

    public static void attach(ImagePlus image, ByteProcessor mask) throws IOException {
        String info = image.getInfoProperty();
        if (info == null) info = "";
        info = info.replaceAll("(?m)^" + KEY + "[^\\r\\n]*(?:\\r?\\n)?", "");
        if (mask != null) {
            if (mask.getWidth() != image.getWidth() || mask.getHeight() != image.getHeight())
                throw new IOException("Размер маски не совпадает со снимком.");
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            ImageIO.write(mask.getBufferedImage(), "png", stream);
            info = KEY + Base64.getEncoder().encodeToString(stream.toByteArray()) + "\n" + info;
        }
        image.setProperty("Info", info);
    }

    public static ByteProcessor read(ImagePlus image) throws IOException {
        String info = image.getInfoProperty();
        if (info == null) return null;
        for (String line : info.split("\n")) {
            if (!line.startsWith(KEY)) continue;
            try {
                byte[] bytes = Base64.getDecoder().decode(line.substring(KEY.length()).trim());
                BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(bytes));
                if (decoded == null || decoded.getWidth() != image.getWidth() || decoded.getHeight() != image.getHeight())
                    throw new IOException("Размер маски области не совпадает со снимком.");
                ByteProcessor mask = new ByteProcessor(image.getWidth(), image.getHeight());
                boolean any = false;
                for (int y = 0; y < mask.getHeight(); y++) for (int x = 0; x < mask.getWidth(); x++) {
                    int value = decoded.getRaster().getSample(x, y, 0);
                    if (value != 0 && value != 255) throw new IOException("Некорректная маска области.");
                    mask.set(x, y, value); any |= value != 0;
                }
                if (!any) throw new IOException("Выбранная область пуста.");
                return mask;
            } catch (IllegalArgumentException error) { throw new IOException("Не удалось прочитать маску области.", error); }
        }
        return null;
    }
}
