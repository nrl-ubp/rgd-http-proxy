package com.ubp.rgd.proxy.queue;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.jms.BytesMessage;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;

/**
 * Sends the transformed messages to the output queue of a route, and those that cannot be transformed
 * to its error queue.
 * <p>
 * A transformed message has the type of the received one (text or bytes) and keeps its correlation id,
 * type, user properties, persistence, priority and remaining time to live. The provider properties
 * ({@code JMS*}) and the reply-to queue, which belongs to the input queue manager, are not copied.
 */
@ApplicationScoped
public class QueueProducer {

    /** The route that failed to transform the message. */
    public static final String ROUTE_PROPERTY = "RgdRoute";
    /** What went wrong, as {@code ExceptionClass: message}. */
    public static final String ERROR_REASON_PROPERTY = "RgdErrorReason";
    /** The class of the root cause of the failure. */
    public static final String ERROR_CLASS_PROPERTY = "RgdErrorClass";
    /** When the transformation failed, ISO-8601. */
    public static final String ERROR_TIME_PROPERTY = "RgdErrorTime";
    /** The message id of the received message. */
    public static final String ORIGINAL_ID_PROPERTY = "RgdOriginalMessageId";

    private static final int MAX_REASON_LENGTH = 1000;

    /**
     * @param session  the sessions of the consumer
     * @param original the received message
     * @param payload  its transformed payload
     */
    public void send(RouteSession session, Message original, String payload) throws JMSException {
        Session output = session.outputSession();
        Message message = original instanceof BytesMessage
                ? bytesMessage(output, payload.getBytes(StandardCharsets.UTF_8))
                : output.createTextMessage(payload);
        copyHeaders(original, message);
        session.outputProducer().send(message, original.getJMSDeliveryMode(), original.getJMSPriority(),
                timeToLive(original));
    }

    /** @return whether the message can be copied to an error queue: only text and bytes bodies are */
    public boolean canCopyToErrorQueue(Message original) {
        return original instanceof TextMessage || original instanceof BytesMessage;
    }

    /**
     * Sends an unchanged copy of the received message to the error queue, with what went wrong in its
     * {@code RgdError*} properties. The copy never expires.
     *
     * @param session  the sessions of the consumer
     * @param original the received message
     * @param route    the name of the route
     * @param failure  why the message could not be transformed
     */
    public void sendToErrorQueue(RouteSession session, Message original, String route, Throwable failure)
            throws JMSException {
        Session error = session.errorSession();
        Message copy = original instanceof TextMessage text
                ? error.createTextMessage(text.getText())
                : bytesMessage(error, original.getBody(byte[].class));
        copyHeaders(original, copy);
        Throwable root = FailureClassifier.rootCause(failure);
        copy.setStringProperty(ROUTE_PROPERTY, route);
        copy.setStringProperty(ERROR_REASON_PROPERTY, reason(failure, root));
        copy.setStringProperty(ERROR_CLASS_PROPERTY, root.getClass().getName());
        copy.setStringProperty(ERROR_TIME_PROPERTY, Instant.now().toString());
        if (original.getJMSMessageID() != null) {
            copy.setStringProperty(ORIGINAL_ID_PROPERTY, original.getJMSMessageID());
        }
        session.errorProducer().send(copy, original.getJMSDeliveryMode(), original.getJMSPriority(),
                Message.DEFAULT_TIME_TO_LIVE);
    }

    private static BytesMessage bytesMessage(Session session, byte[] body) throws JMSException {
        BytesMessage message = session.createBytesMessage();
        if (body != null) {
            message.writeBytes(body);
        }
        return message;
    }

    private static void copyHeaders(Message from, Message to) throws JMSException {
        to.setJMSCorrelationID(from.getJMSCorrelationID());
        to.setJMSType(from.getJMSType());
        for (Object name : Collections.list(from.getPropertyNames())) {
            String property = (String) name;
            if (!property.startsWith("JMS")) {
                to.setObjectProperty(property, from.getObjectProperty(property));
            }
        }
    }

    /** @return the time the received message had left to live, 0 for unlimited */
    static long timeToLive(Message original) throws JMSException {
        long expiration = original.getJMSExpiration();
        if (expiration <= 0) {
            return Message.DEFAULT_TIME_TO_LIVE;
        }
        return Math.max(1, expiration - System.currentTimeMillis());
    }

    private static String reason(Throwable failure, Throwable root) {
        String message = root.getMessage() != null ? root.getMessage() : failure.getMessage();
        String reason = root.getClass().getSimpleName() + (message == null ? "" : ": " + message);
        return reason.length() <= MAX_REASON_LENGTH ? reason : reason.substring(0, MAX_REASON_LENGTH);
    }
}
