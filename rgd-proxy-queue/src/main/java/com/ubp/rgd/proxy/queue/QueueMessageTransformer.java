package com.ubp.rgd.proxy.queue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ubp.rgd.proxy.queue.config.QueueRouteConfig;
import com.ubp.rgd.proxy.transform.DefaultSecretsManagerProvider;
import com.ubp.rgd.proxy.transform.EndPointTransformer;
import com.ubp.rgd.proxy.transform.TransformException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.jms.BytesMessage;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Transforms the JSON payload of a message with the injected {@link EndPointTransformer}, the one
 * {@code proxy.transform.impl} selects, exactly like an HTTP payload or a file.
 * <p>
 * The payload is the text of a {@link TextMessage}, or the UTF-8 content of a {@link BytesMessage}, and
 * must be a JSON object or array. Anything else is rejected rather than forwarded: the transformer reads
 * JSON leniently and would pass an unparsable payload through unchanged, which on a protecting route
 * means sending it in clear.
 */
@ApplicationScoped
public class QueueMessageTransformer {

    private static final ObjectMapper STRICT_JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    @Inject
    EndPointTransformer transformer;

    @Inject
    DefaultSecretsManagerProvider secretsManagers;

    /**
     * @param route   the route, giving the contexts and the entity transformations
     * @param message the received message
     * @return the transformed payload
     * @throws InvalidMessageException    when the message is neither a text nor a bytes message, or its
     *                                    payload is not a JSON object or array
     * @throws TransformException          when the transformer fails
     * @throws JMSException                when the body cannot be read
     */
    public String transform(QueueRouteConfig route, Message message)
            throws InvalidMessageException, TransformException, JMSException {
        String payload = payload(message);
        requireJsonDocument(payload);
        return transformer.transformJson(
                payload,
                route.sortAttributeTransformsConfig(),
                route.getRightContextEvidences(),
                route.getProcessingContextEvidences(),
                secretsManagerFor(route));
    }

    /**
     * Protecting uses the default secrets manager of the mapping file. Unprotecting uses none, so
     * that the transformer resolves the secrets manager that created each token.
     */
    UUID secretsManagerFor(QueueRouteConfig route) {
        return route.unprotects() ? null : secretsManagers.defaultSecretsManager();
    }

    static String payload(Message message) throws InvalidMessageException, JMSException {
        if (message instanceof TextMessage text) {
            String body = text.getText();
            return body == null ? "" : body;
        }
        if (message instanceof BytesMessage) {
            byte[] body = message.getBody(byte[].class);
            return body == null ? "" : new String(body, StandardCharsets.UTF_8);
        }
        throw new InvalidMessageException("Unsupported message type " + message.getClass().getSimpleName()
                + ": only text and bytes messages carry a JSON payload");
    }

    static void requireJsonDocument(String payload) throws InvalidMessageException {
        JsonNode document;
        try {
            document = STRICT_JSON.readTree(payload);
        } catch (JsonProcessingException e) {
            throw new InvalidMessageException("The payload is not valid JSON: " + e.getOriginalMessage());
        }
        if (document == null || !document.isContainerNode()) {
            throw new InvalidMessageException("The payload is not a JSON object or array");
        }
    }
}
