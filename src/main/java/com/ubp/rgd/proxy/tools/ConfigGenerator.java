package com.ubp.rgd.proxy.tools;

import com.ubp.rgd.proxy.transform.config.EntityTransformConfig;
import com.ubp.rgd.proxy.transform.config.EndPointTransformConfig;
import com.ubp.rgd.proxy.utils.JSONFile;

import java.io.File;
import java.io.IOException;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public class ConfigGenerator {

    public static Logger quickConsoleLogger(Class<?> clazz, Level level) {
        Logger logger = Logger.getLogger(clazz.getName());
        logger.setUseParentHandlers(false);
        ConsoleHandler ch = new ConsoleHandler();
        ch.setLevel(level);
        ch.setFormatter(new SimpleFormatter());
        logger.addHandler(ch);
        logger.setLevel(level);
        return logger;
    }

    private static final Logger LOG = quickConsoleLogger(ConfigGenerator.class, Level.FINE);

    public static void main(String... args) {
        EndPointTransformConfig cfg = new EndPointTransformConfig();
        EntityTransformConfig attrcfg = new EntityTransformConfig();
        attrcfg.setJsonPath("$.firstName");
        attrcfg.setRpsClassName("poc.Person");
        attrcfg.setRpsPropertyName("Name");
        cfg.getAttributeTransformConfigs().add(attrcfg);

        attrcfg = new EntityTransformConfig();
        attrcfg.setJsonPath("$.lastName");
        attrcfg.setRpsClassName("poc.Person");
        attrcfg.setRpsPropertyName("Name");
        cfg.getAttributeTransformConfigs().add(attrcfg);

        cfg.setEndpointPath("/api/v1/persons/\\d+");

        cfg.getRightContextEvidences().put("Location", "onshore");

        cfg.getProcessingContextEvidences().put("Location", "onshore");
        cfg.getProcessingContextEvidences().put("Action", "protect");

        try {
            LOG.info("RPS transform configuration file generator...");
            String configFilePath = "./config/rps_transform_config.json";
            JSONFile.saveAs(new File(configFilePath), cfg);
            LOG.info(String.format("SAMPLE Config saved into: %s%n", configFilePath));
            LOG.info("Now edit config file to suit your needs.");
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Problem while generating configuration: ", e);
        }
    }
}
