package ch.regdata.rps.engine.client.model.api.response;

import ch.regdata.rps.engine.client.model.api.Instance;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class Response {

    private UUID request;
    private UUID rightsContext;
    private UUID processingContext;
    private List<Instance> instances = new ArrayList<Instance>();

    public void addInstance(Instance instance) {
        instances.add(instance);
    }

    public UUID getRequest() {
        return request;
    }

    public void setRequest(UUID request) {
        this.request = request;
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

    public List<Instance> getInstances() {
        return instances;
    }

    public void setInstances(List<Instance> instances) {
        this.instances = instances;
    }
}
