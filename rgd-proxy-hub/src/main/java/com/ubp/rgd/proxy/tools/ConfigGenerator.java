package com.ubp.rgd.proxy.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.ubp.rgd.proxy.transform.config.EntityTransformConfig;
import com.ubp.rgd.proxy.transform.config.EndPointTransformConfig;
import com.ubp.rgd.proxy.utils.JSONFile;

import java.io.File;
import java.io.IOException;

public class ConfigGenerator {

    private static final Logger LOG = LoggerFactory.getLogger(ConfigGenerator.class);

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
            LOG.info("SAMPLE Config saved into: {}", configFilePath);
            LOG.info("Now edit config file to suit your needs.");
        } catch (IOException e) {
            LOG.error("Problem while generating configuration: ", e);
        }
    }
}
