package com.ubp.rgd.proxy.fuzzysearch.service;

import com.ubp.rgd.proxy.fuzzysearch.FuzzySearch;
import com.ubp.rgd.proxy.fuzzysearch.service.search.TrainingDataService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RestClient;

/**
 * Readiness of the fuzzy search.
 * <ul>
 *     <li>Disabled: UP, the proxy must not be reported down because of a module it does not run.</li>
 *     <li>Enabled: UP when OpenSearch answers, DOWN otherwise. The size of the training vocabulary is
 *     reported, -1 while it is being built: the search works meanwhile, without neighbours.</li>
 * </ul>
 * The database is covered by the datasource health check of Quarkus, active with the module only.
 */
@Readiness
@ApplicationScoped
public class FuzzySearchHealthCheck implements HealthCheck {

    @ConfigProperty(name = FuzzySearch.ENABLED_PROPERTY, defaultValue = "false")
    boolean enabled;

    @Inject
    Instance<RestClient> restClient;

    @Inject
    TrainingDataService trainingDataService;

    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder builder = HealthCheckResponse.named("Fuzzy search").withData("enabled", enabled);
        if (!enabled) {
            return builder.up().build();
        }
        builder.withData("vocabularySize", trainingDataService.lastVocabularySize())
                .withData("running", trainingDataService.isRunning());
        try {
            int status = restClient.get().performRequest(new Request("GET", "/")).getStatusLine().getStatusCode();
            return builder.status(status / 100 == 2).withData("opensearch", "HTTP " + status).build();
        } catch (Exception e) {
            return builder.down().withData("opensearch", String.valueOf(e.getMessage())).build();
        }
    }
}
