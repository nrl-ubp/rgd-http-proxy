package com.ubp.rgd.proxy.transform;

import ch.regdata.rps.engine.client.http.HttpClientEngineProvider;
import io.quarkus.logging.Log;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Singleton
public class RPSClientEngineProvider {

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
            Log.infof("Creating RPS client connection with ID: %s", clientId);
            Log.infof("Transform identity URL: %s", identityUrl);
            Log.infof("Transform engine URL: %s", engineUrl);

            engineProvider = new HttpClientEngineProvider();
            engineProvider.setApiKey(clientId);
            engineProvider.setEngineHost(engineUrl);
            engineProvider.setSecretKey(clientSecret);
            engineProvider.setIdentityHost(identityUrl);
        }

        return engineProvider;
    }
}
