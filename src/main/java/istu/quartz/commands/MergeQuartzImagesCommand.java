package istu.quartz.commands;

import istu.quartz.ui.StackMergeWindow;
import org.scijava.command.Command;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import javax.swing.SwingUtilities;

@Plugin(type = Command.class, menuPath = "Plugins>Quartz Segmentation>Merge Images…")
public class MergeQuartzImagesCommand implements Command {
    @Parameter
    private LogService log;

    @Override
    public void run() {
        SwingUtilities.invokeLater(() -> new StackMergeWindow(log).setVisible(true));
    }
}
