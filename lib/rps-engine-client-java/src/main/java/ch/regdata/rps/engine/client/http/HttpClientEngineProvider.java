package ch.regdata.rps.engine.client.http;

import ch.regdata.rps.engine.client.engine.IRPSEngineProvider;
import ch.regdata.rps.engine.client.model.api.connect.Token;
import ch.regdata.rps.engine.client.model.api.request.RequestBody;
import ch.regdata.rps.engine.client.model.api.response.ResponseBody;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.apache.hc.client5.http.ClientProtocolException;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.entity.UrlEncodedFormEntity;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.io.HttpClientConnectionManager;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.NameValuePair;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicNameValuePair;
import org.apache.hc.core5.ssl.SSLContexts;
import org.apache.hc.core5.ssl.TrustStrategy;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * This class provides an implementation of the IRPSEngineProvider for an http client
 */
public class HttpClientEngineProvider implements IRPSEngineProvider {

    private static final String ENGINE_TOKEN = "RPSEngine";     // Name of the token used for the engine

    private String engineHost;
    private String tokenTimeOut = "300";                // Default - 300 minutes
    private String identityHost;
    private String apiKey;
    private String secretKey;
    private boolean acceptSelSigned = false;
    private String token = "";

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();

    public HttpClientEngineProvider() {

    }

    /**
     * For testing purpose tells if we accept self-signed certificates
     *
     * @param accept
     */
    public void setAcceptSelSigned(boolean accept) {
        this.acceptSelSigned = accept;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public String getEngineHost() {
        return engineHost;
    }

    public void setEngineHost(String engineHost) {
        this.engineHost = engineHost;
    }

    public String getTokenTimeOut() {
        return tokenTimeOut;
    }

    public void setTokenTimeOut(String tokenTimeOut) {
        this.tokenTimeOut = tokenTimeOut;
    }

    public String getIdentityHost() {
        return identityHost;
    }

    public void setIdentityHost(String identityHost) {
        this.identityHost = identityHost;
    }

    /**
     * Calls the RPS Engine transform API
     *
     * @param requestBody
     * @return
     */
    @Override
    public ResponseBody transformAsync(RequestBody requestBody) {
        try {
            // Get authentication token
            String bearer = token;

            CloseableHttpClient httpClient = getHttpClient(acceptSelSigned);
            String authorizationString = "Bearer " + bearer;    // Authorization string

            HttpPost post = new HttpPost(engineHost + "/api/transform");
            post.setHeader("Authorization", authorizationString);
            post.setHeader("Content-Type", "application/json");
            // Get Json request
            String jsonRequest = MAPPER.writeValueAsString(requestBody);
            post.setEntity(new StringEntity(jsonRequest, ContentType.APPLICATION_JSON));

            // Returns null when unauthorized so that we can re-authenticate and retry
            HttpClientResponseHandler<String> responseHandler = response -> {
                if (response.getCode() == HttpStatus.SC_UNAUTHORIZED) {
                    EntityUtils.consume(response.getEntity());
                    return null;
                }
                HttpEntity responseEntity = response.getEntity();
                return responseEntity != null ? EntityUtils.toString(responseEntity, StandardCharsets.UTF_8) : null;
            };

            try {
                String responseBody = httpClient.execute(post, responseHandler);
                if (responseBody == null) {
                    token = authenticate();
                    authorizationString = "Bearer " + token;
                    post.setHeader("Authorization", authorizationString);
                    responseBody = httpClient.execute(post, responseHandler);
                }
                // Deserialize JSon response to ResponseBody object
                ResponseBody body = responseBody != null ? MAPPER.readValue(responseBody, ResponseBody.class)
                        : new ResponseBody();
                return body;
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                closeConnection(httpClient);
            }

            return null;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * Authenticates against RPSIdentity and returns the access_token
     *
     * @return java.lang.String access token
     */
    public String authenticate() {

        // Uses a form application/x-www-form-urlencoded
        List<NameValuePair> formparams = new ArrayList<>();
        formparams.add(new BasicNameValuePair("grant_type", "client_credentials"));
        formparams.add(new BasicNameValuePair("client_id", apiKey));
        formparams.add(new BasicNameValuePair("client_secret", secretKey));
        UrlEncodedFormEntity entity = new UrlEncodedFormEntity(formparams, StandardCharsets.UTF_8);
        // Set http request
        CloseableHttpClient httpClient = getHttpClient(acceptSelSigned);
        HttpPost httpPost = new HttpPost(identityHost + "/connect/token");
        httpPost.setEntity(entity);

        // Handle the response as a string
        HttpClientResponseHandler<String> responseHandler = response -> {
            int status = response.getCode();
            if (status == 200) {
                HttpEntity responseEntity = response.getEntity();
                return responseEntity != null ? EntityUtils.toString(responseEntity) : null;
            } else {
                System.out.println("ERROR Authentication method returned " + status);
                throw new ClientProtocolException("Unexpected response status: " + status);
            }
        };

        try {
            String responseBody = httpClient.execute(httpPost, responseHandler);
            Token token = MAPPER.readValue(responseBody, Token.class);
            return token.getAccess_token();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            closeConnection(httpClient);
        }
        return null;
    }

    /**
     * Closes an HttpClient once used
     *
     * @param httpClient
     */
    private void closeConnection(CloseableHttpClient httpClient) {
        try {
            httpClient.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Creates the http connection. This method can accept self signed certificates for testing.
     * This method is usefull when test certificates are self-signed and production ones are signed
     * In test we can set this value to true
     * While it has to be set to false in production
     *
     * @param withSelfSigned true if a self signed certificate can be accepted
     * @return
     */
    private CloseableHttpClient getHttpClient(boolean withSelfSigned) {
        if (withSelfSigned) {   // Accept self-signed certificates
            TrustStrategy acceptingTrustStrategy = (cert, authType) -> true;
            SSLContext sslContext = null;
            try {
                sslContext = SSLContexts.custom().loadTrustMaterial(null, acceptingTrustStrategy).build();
            } catch (Exception e) {
                e.printStackTrace();
            }
            HttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                    .setTlsSocketStrategy(new DefaultClientTlsStrategy(sslContext, NoopHostnameVerifier.INSTANCE))
                    .build();
            CloseableHttpClient httpClient = HttpClients.custom()
                    .setConnectionManager(connectionManager).build();
            return httpClient;
        } else {    // Signed certificates
            return HttpClients.createDefault();
        }
    }
}
