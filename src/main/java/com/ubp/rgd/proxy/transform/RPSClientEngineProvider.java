package com.ubp.rgd.proxy.transform;

import ch.regdata.rps.engine.client.http.HttpClientEngineProvider;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class RPSClientEngineProvider {

    private static final Logger LOG = LoggerFactory.getLogger(RPSClientEngineProvider.class);

    @ConfigProperty(name = "proxy.transform.client-id")
    private String clientId;

    @ConfigProperty(name = "proxy.transform.client-secret")
    private String clientSecret;

    @ConfigProperty(name = "proxy.transform.identity-url")
    private String identityUrl;

    @ConfigProperty(name = "proxy.transform.engine-url")
    private String engineUrl;

    private HttpClientEngineProvider engineProvider;

    public synchronized HttpClientEngineProvider getClientEngineProvider() {
        if (engineProvider == null) {
            LOG.info("Creating RPS client connection with ID: {}", clientId);
            LOG.info("Transform identity URL: {}", identityUrl);
            LOG.info("Transform engine URL: {}", engineUrl);

            engineProvider = new HttpClientEngineProvider();
            engineProvider.setApiKey(clientId);
            engineProvider.setEngineHost(engineUrl);
            engineProvider.setSecretKey(clientSecret);
            engineProvider.setIdentityHost(identityUrl);
        }

        return engineProvider;
    }
}
