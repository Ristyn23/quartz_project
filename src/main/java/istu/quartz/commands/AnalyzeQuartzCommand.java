package istu.quartz.commands;

import istu.quartz.ui.QuartzWindow;
import org.scijava.command.Command;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import javax.swing.SwingUtilities;

@Plugin(type = Command.class, menuPath = "Plugins>Quartz>Analyze Quartz")
public class AnalyzeQuartzCommand implements Command {

    @Parameter
    private LogService log;

    @Override
    public void run() {
        SwingUtilities.invokeLater(() -> new QuartzWindow(log).setVisible(true));
    }
}
