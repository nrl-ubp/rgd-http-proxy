package ch.regdata.rps.engine.client;

import ch.regdata.rps.engine.client.model.api.value.IRPSValueBase;

import java.util.UUID;

/**
 * Represent a single request object to RPS Engine.
 * Note: RPS Engine support multiple Request[] into a single call to RPS Engine.
 */
public class Request {

    private UUID guid = UUID.randomUUID();
    private IRPSValueBase[] values;
    private Context rightContext;
    private Context processingContext;
    private Context loggingContext;
    private UUID secretsManager;

    public Context getLoggingContext() {
        return loggingContext;
    }

    public void setLoggingContext(Context loggingContext) {
        this.loggingContext = loggingContext;
    }

    public UUID getGuid() {
        return guid;
    }

    public void setGuid(UUID guid) {
        this.guid = guid;
    }

    public IRPSValueBase[] getValues() {
        return values;
    }

    public void setValues(IRPSValueBase[] values) {
        this.values = values;
    }

    public Context getRightContext() {
        return rightContext;
    }

    public void setRightContext(Context rightContext) {
        this.rightContext = rightContext;
    }

    public UUID getSecretsManager() {
        return secretsManager;
    }

    public void setSecretsManager(UUID secretsManager) {
        this.secretsManager = secretsManager;
    }

    public Context getProcessingContext() {
        return processingContext;
    }

    public void setProcessingContext(Context processingContext) {
        this.processingContext = processingContext;
    }

}
