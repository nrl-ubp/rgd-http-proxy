package com.ubp.rgd.proxy.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ubp.rgd.proxy.transform.config.EntityTransformConfig;
import com.ubp.rgd.proxy.transform.config.FileTransformConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class FileTransformConfigTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    void testFileTransformConfigSerialization() throws Exception {
        // Create a FileTransformConfig
        FileTransformConfig config = new FileTransformConfig();
        config.setName("Test Config");
        config.setSourceDirectory("./data/source");
        config.setTargetDirectory("./data/target");
        config.setErrorDirectory("./data/error");
        config.setWorkInProgressSuffix(".processing");
        config.setFilePattern(".*\\.json$");
        config.setPreserveDirectoryStructure(true);

        // Set right context
        HashMap<String, String> rightContext = new HashMap<>();
        rightContext.put("Location", "Onshore");
        config.setRightContextEvidences(rightContext);

        // Set processing context
        Map<String, String> processingContext = new HashMap<>();
        processingContext.put("Action", "Protect");
        processingContext.put("Location", "Onshore");
        config.setProcessingContextEvidences(processingContext);

        // Add entity transform configs
        Set<EntityTransformConfig> entityConfigs = new HashSet<>();
        
        EntityTransformConfig nameConfig = new EntityTransformConfig();
        nameConfig.setJsonPath("$.firstName");
        nameConfig.setRpsClassName("poc.Person");
        nameConfig.setRpsPropertyName("Name");
        entityConfigs.add(nameConfig);

        EntityTransformConfig birthDateConfig = new EntityTransformConfig();
        birthDateConfig.setJsonPath("$.birthDate");
        birthDateConfig.setRpsClassName("poc.Person");
        birthDateConfig.setRpsPropertyName("BirthDate");
        entityConfigs.add(birthDateConfig);

        config.setEntityTransformConfigs(entityConfigs);

        // Serialize to JSON
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(config);
        
        System.out.println("Serialized Config:");
        System.out.println(json);

        // Deserialize back
        FileTransformConfig deserialized = objectMapper.readValue(json, FileTransformConfig.class);

        // Verify
        assertEquals("Test Config", deserialized.getName());
        assertEquals("./data/source", deserialized.getSourceDirectory());
        assertEquals("./data/target", deserialized.getTargetDirectory());
        assertEquals("./data/error", deserialized.getErrorDirectory());
        assertEquals(".processing", deserialized.getWorkInProgressSuffix());
        assertEquals(".*\\.json$", deserialized.getFilePattern());
        assertTrue(deserialized.isPreserveDirectoryStructure());
        assertEquals("Onshore", deserialized.getRightContextEvidences().get("Location"));
        assertEquals("Protect", deserialized.getProcessingContextEvidences().get("Action"));
        assertEquals(2, deserialized.getEntityTransformConfigs().size());
    }

    @Test
    void testFileTransformConfigListSerialization(@TempDir Path tempDir) throws Exception {
        // Create a list of configs
        List<FileTransformConfig> configs = new ArrayList<>();

        // Config 1: Protect
        FileTransformConfig protectConfig = new FileTransformConfig();
        protectConfig.setName("Person Data Protection");
        protectConfig.setSourceDirectory("./data/source");
        protectConfig.setTargetDirectory("./data/target");
        protectConfig.setErrorDirectory("./data/error");

        Map<String, String> protectContext = new HashMap<>();
        protectContext.put("Action", "Protect");
        protectContext.put("Location", "Onshore");
        protectConfig.setProcessingContextEvidences(protectContext);

        Set<EntityTransformConfig> protectEntityConfigs = new HashSet<>();
        EntityTransformConfig firstName = new EntityTransformConfig();
        firstName.setJsonPath("$.firstName");
        firstName.setRpsClassName("poc.Person");
        firstName.setRpsPropertyName("Name");
        protectEntityConfigs.add(firstName);
        protectConfig.setEntityTransformConfigs(protectEntityConfigs);

        configs.add(protectConfig);

        // Config 2: Unprotect
        FileTransformConfig unprotectConfig = new FileTransformConfig();
        unprotectConfig.setName("Person Data Unprotection");
        unprotectConfig.setSourceDirectory("./data/unprotect-source");
        unprotectConfig.setTargetDirectory("./data/unprotect-target");
        unprotectConfig.setErrorDirectory("./data/unprotect-error");

        Map<String, String> unprotectContext = new HashMap<>();
        unprotectContext.put("Action", "Unprotect");
        unprotectContext.put("Location", "Onshore");
        unprotectConfig.setProcessingContextEvidences(unprotectContext);

        Set<EntityTransformConfig> unprotectEntityConfigs = new HashSet<>();
        EntityTransformConfig lastName = new EntityTransformConfig();
        lastName.setJsonPath("$.lastName");
        lastName.setRpsClassName("poc.Person");
        lastName.setRpsPropertyName("Name");
        unprotectEntityConfigs.add(lastName);
        unprotectConfig.setEntityTransformConfigs(unprotectEntityConfigs);

        configs.add(unprotectConfig);

        // Write to file
        Path configFile = tempDir.resolve("test_file_transform_config.json");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(configFile.toFile(), configs);

        System.out.println("\nGenerated config file at: " + configFile);
        System.out.println(Files.readString(configFile));

        // Read back
        List<FileTransformConfig> readConfigs = objectMapper.readValue(
            configFile.toFile(),
            objectMapper.getTypeFactory().constructCollectionType(List.class, FileTransformConfig.class)
        );

        // Verify
        assertEquals(2, readConfigs.size());
        assertEquals("Person Data Protection", readConfigs.get(0).getName());
        assertEquals("Person Data Unprotection", readConfigs.get(1).getName());
        assertEquals("Protect", readConfigs.get(0).getProcessingContextEvidences().get("Action"));
        assertEquals("Unprotect", readConfigs.get(1).getProcessingContextEvidences().get("Action"));
    }

    @Test
    void testSortAttributeTransformsConfig() {
        FileTransformConfig config = new FileTransformConfig();
        
        Set<EntityTransformConfig> entityConfigs = new HashSet<>();
        
        EntityTransformConfig config1 = new EntityTransformConfig();
        config1.setJsonPath("$.firstName");
        config1.setRpsClassName("poc.Person");
        config1.setRpsPropertyName("Name");
        entityConfigs.add(config1);

        EntityTransformConfig config2 = new EntityTransformConfig();
        config2.setJsonPath("$.lastName");
        config2.setRpsClassName("poc.Person");
        config2.setRpsPropertyName("Name");
        entityConfigs.add(config2);

        config.setEntityTransformConfigs(entityConfigs);

        // Get sorted map
        Map<String, EntityTransformConfig> sorted = config.sortAttributeTransformsConfig();

        assertEquals(2, sorted.size());
        assertNotNull(sorted.get("$.firstName"));
        assertNotNull(sorted.get("$.lastName"));
        assertEquals("Name", sorted.get("$.firstName").getRpsPropertyName());
    }
}
