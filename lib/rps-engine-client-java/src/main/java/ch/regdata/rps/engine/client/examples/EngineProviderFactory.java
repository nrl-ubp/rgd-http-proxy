package ch.regdata.rps.engine.client.examples;

import ch.regdata.rps.engine.client.http.HttpClientEngineProvider;

public abstract class EngineProviderFactory {
    public static HttpClientEngineProvider getEngineProvider() {
        // Configuration options for RPS Engine Client library.
        final String engineHostName = "https://engine.rpsprod.ch";// Example: "https://engine.rpsprod.ch"
        final String identityHostName = "https://identity.rpsprod.ch";  // Example: "https://identity.rpsprod.ch",
        final String apiKey = "*********************"; // Example: "518155d0-4774-467c-b891-6ebd3ea07f08"
        final String secretKey = "********************"; // Example: "75582a544bdasa23easdasdaadssd5f60369b9866026d8485aa49f7edcfe986bb1"

        // Set the engine provider with connection details
        HttpClientEngineProvider engineProvider = new HttpClientEngineProvider();
        engineProvider.setApiKey(apiKey);
        engineProvider.setSecretKey(secretKey);
        engineProvider.setIdentityHost(identityHostName);
        engineProvider.setEngineHost(engineHostName);

        return engineProvider;
    }
}
