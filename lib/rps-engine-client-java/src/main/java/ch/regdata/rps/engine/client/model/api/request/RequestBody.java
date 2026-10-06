package ch.regdata.rps.engine.client.model.api.request;

import ch.regdata.rps.engine.client.model.api.context.Context;

import java.util.ArrayList;
import java.util.List;

public class RequestBody {

    private List<Context> rightsContexts = new ArrayList<Context>();
    private List<Context> processingContexts = new ArrayList<Context>();
    private List<Request> requests = new ArrayList<Request>();

    public void addRightContext(Context rightContext) {
        rightsContexts.add(rightContext);
    }

    public void addProcessingContext(Context processingContext) {
        processingContexts.add(processingContext);
    }

    public List<Context> getRightsContexts() {
        return rightsContexts;
    }

    public void setRightsContexts(List<Context> rightContexts) {
        this.rightsContexts = rightContexts;
    }

    public List<Context> getProcessingContexts() {
        return processingContexts;
    }

    public void setProcessingContexts(List<Context> processingContexts) {
        this.processingContexts = processingContexts;
    }

    public List<Request> getRequests() {
        return requests;
    }

    public void setRequests(List<Request> requests) {
        this.requests = requests;
    }
}
