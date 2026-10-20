package ch.regdata.rps.engine.client.model.api.request;

import ch.regdata.rps.engine.client.model.api.Instance;
import ch.regdata.rps.engine.client.model.api.context.Context;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class Request {

    private UUID guid;
    private Context loggingContext;
    private UUID rightsContext;
    private UUID processingContext;
    private UUID secretsManager;
    private List<Instance> instances = new ArrayList<Instance>();

    public void addInstance(Instance instance) {
        instances.add(instance);
    }

    public UUID getGuid() {
        return guid;
    }

    public void setGuid(UUID guid) {
        this.guid = guid;
    }

    public Context getLoggingContext() {
        return loggingContext;
    }

    public void setLoggingContext(Context loggingContext) {
        this.loggingContext = loggingContext;
    }

    public UUID getRightsContext() {
        return rightsContext;
    }

    public void setRightsContext(UUID rightContext) {
        this.rightsContext = rightContext;
    }

    public UUID getProcessingContext() {
        return processingContext;
    }

    public void setProcessingContext(UUID processingContext) {
        this.processingContext = processingContext;
    }

    public UUID getSecretsManager() {
        return secretsManager;
    }

    public void setSecretsManager(UUID secretsManager) {
        this.secretsManager = secretsManager;
    }

    public List<Instance> getInstances() {
        return instances;
    }

    public void setInstances(List<Instance> instances) {
        this.instances = instances;
    }
}
