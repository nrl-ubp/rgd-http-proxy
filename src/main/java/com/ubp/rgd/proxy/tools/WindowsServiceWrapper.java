package com.ubp.rgd.proxy.tools;

import com.ubp.rgd.proxy.HttpProxyApplication;
import org.jboss.logging.Logger;

/**
 * Wrapper class to allow running the Quarkus application as a Windows Service using Apache Commons Procrun.
 * Procrun calls the main method with "start" or "stop" arguments.
 * In jvm mode, the start and stop calls happen in the same JVM instance.
 */
public class WindowsServiceWrapper {

    private static final Logger LOG = Logger.getLogger(WindowsServiceWrapper.class);

    public static void main(String[] args) {
        if (args.length == 0) {
            LOG.error("No argument provided. Expected 'start' or 'stop'.");
            return;
        }

        String command = args[0].toLowerCase();
        LOG.infof("WindowsServiceWrapper received command: %s", command);

        switch (command) {
            case "start":
                // Remove the "start" argument before passing to Quarkus to avoid confusing its CLI parser
                String[] quarkusArgs = new String[args.length - 1];
                System.arraycopy(args, 1, quarkusArgs, 0, args.length - 1);
                HttpProxyApplication.main(quarkusArgs);
                break;
            case "stop":
                LOG.info("Stopping Quarkus application...");
                // In jvm mode, this will cause the JVM to exit, stopping the service.
                System.exit(0);
                break;
            default:
                LOG.errorf("Unknown command: %s. Expected 'start' or 'stop'.", command);
                break;
        }
    }
}