package com.ubp.rgd.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GenerateTrainingDataTest {

    @Test
    @DisplayName("The database source calls the training data endpoint, or the reindex one")
    void endpoint() {
        assertEquals(URI.create("http://localhost:8080/api/fuzzy-search/admin/training-data"),
                GenerateTrainingData.endpoint("http://localhost:8080/", false, null));
        assertEquals(URI.create("http://search:9000/api/fuzzy-search/admin/reindex?batchSize=250"),
                GenerateTrainingData.endpoint("http://search:9000", true, " 250"));
    }

    @Test
    @DisplayName("An unknown source is a usage error")
    void unknownSource() throws Exception {
        assertEquals(2, GenerateTrainingData.run(new String[]{"--source", "csv"}));
    }
}
