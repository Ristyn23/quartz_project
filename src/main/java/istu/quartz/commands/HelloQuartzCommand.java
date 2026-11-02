package istu.quartz.commands;

import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.log.LogService;

@Plugin(type = Command.class, name = "Hello Quartz")
public class HelloQuartzCommand implements Command {

    @Parameter
    private LogService log;

    @Parameter
    private String greeting = "Hello from Quartz plugin!";

    @Override
    public void run() {
        log.info(greeting);
        System.out.println(greeting);
    }
}