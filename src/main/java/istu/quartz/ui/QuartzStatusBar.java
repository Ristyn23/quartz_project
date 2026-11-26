package istu.quartz.ui;

import javax.swing.*;
import java.awt.*;

/** A fixed-height footer: processing in the centre, status and zoom at the right. */
final class QuartzStatusBar extends JPanel {
    private final JProgressBar progress = new JProgressBar(0, 100);
    private final JPanel right = new JPanel(new FlowLayout(FlowLayout.TRAILING, 5, 0));
    private final JButton minus = new JButton("−");
    private final JButton plus = new JButton("+");
    private final JSlider zoom = new JSlider(0, 1000);
    private final JLabel percent = new JLabel("—", SwingConstants.RIGHT);
    private boolean updating;

    QuartzStatusBar(ImagePreview preview, ImagePreview.ViewState view, JButton fit, JLabel status) {
        setLayout(null);
        status.setPreferredSize(new Dimension(205, 26));
        status.setHorizontalAlignment(SwingConstants.RIGHT);
        status.addPropertyChangeListener("text", e -> status.setToolTipText(status.getText()));
        status.setToolTipText(status.getText());
        fit.setText("Вписать");
        fit.setToolTipText("Вписать изображение целиком");
        minus.setMargin(new Insets(2, 6, 2, 6));
        plus.setMargin(new Insets(2, 6, 2, 6));
        minus.setToolTipText("Уменьшить масштаб");
        plus.setToolTipText("Увеличить масштаб");
        zoom.setPreferredSize(new Dimension(110, 26));
        zoom.setToolTipText("Масштаб изображения");
        percent.setPreferredSize(new Dimension(53, 26));
        percent.setToolTipText("100% — натуральный размер изображения");
        right.add(status);
        right.add(fit);
        right.add(minus);
        right.add(zoom);
        right.add(plus);
        right.add(percent);
        add(right);
        add(progress);
        progress.setStringPainted(true);
        progress.setVisible(false);
        setPreferredSize(new Dimension(800, 32));
        minus.addActionListener(e -> preview.zoomBy(1 / 1.2));
        plus.addActionListener(e -> preview.zoomBy(1.2));
        zoom.addChangeListener(e -> {
            if (!updating) preview.zoomTo(ImagePreview.MIN_SCALE * Math.pow(
                    ImagePreview.MAX_SCALE / ImagePreview.MIN_SCALE, zoom.getValue() / 1000.0));
        });
        Runnable update = () -> {
            double scale = preview.imageScale();
            boolean available = Double.isFinite(scale);
            minus.setEnabled(available);
            plus.setEnabled(available);
            zoom.setEnabled(available);
            if (available) {
                percent.setText(String.format(java.util.Locale.ROOT, scale < 0.01 ? "%.1f%%" : "%.0f%%", 100 * scale));
                updating = true;
                try {
                    zoom.setValue((int) Math.round(1000 * Math.log(scale / ImagePreview.MIN_SCALE)
                            / Math.log(ImagePreview.MAX_SCALE / ImagePreview.MIN_SCALE)));
                } finally { updating = false; }
            } else percent.setText("—");
        };
        view.addChangeListener(update);
        update.run();
    }

    @Override
    public void doLayout() {
        int rightWidth = Math.min(getWidth(), right.getPreferredSize().width);
        int rightX = getWidth() - rightWidth;
        right.setBounds(rightX, (getHeight() - 26) / 2, rightWidth, 26);
        int width = Math.min(160, Math.max(0, rightX - 12));
        int x = Math.max(0, Math.min((getWidth() - width) / 2, rightX - 12 - width));
        progress.setBounds(x, (getHeight() - 20) / 2, width, 20);
    }

    void setWorking(boolean working) {
        progress.setVisible(working);
        progress.setIndeterminate(working);
        progress.setStringPainted(!working);
        progress.setValue(0);
    }

    void setIndeterminate() {
        progress.setIndeterminate(true);
        progress.setStringPainted(false);
    }

    void setCompleted(int completed, int total) {
        if (total <= 0) return;
        progress.setIndeterminate(false);
        progress.setStringPainted(true);
        progress.setValue((int) Math.round(100.0 * completed / total));
    }
}
