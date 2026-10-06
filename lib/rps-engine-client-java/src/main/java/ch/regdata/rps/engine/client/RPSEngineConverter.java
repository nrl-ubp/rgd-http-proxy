package ch.regdata.rps.engine.client;


import ch.regdata.rps.engine.client.extensions.ContextExtensions;
import ch.regdata.rps.engine.client.mapping.RPSMapping;
import ch.regdata.rps.engine.client.model.api.Instance;
import ch.regdata.rps.engine.client.model.api.request.RequestBody;
import ch.regdata.rps.engine.client.model.api.response.Response;
import ch.regdata.rps.engine.client.model.api.response.ResponseBody;
import ch.regdata.rps.engine.client.model.api.value.IRPSValueBase;

import java.util.*;

/**
 * This class provides a set of utility method to translate API and Model objects
 */
public class RPSEngineConverter {
    /**
     * Transforms an API.Request to a Model.Request
     *
     * @param rpsValues
     * @param requestGuid
     * @param rightsContextGuid
     * @param processingContextGuid
     * @param loggingContext
     * @param secretsManager
     * @return
     */
    private static ch.regdata.rps.engine.client.model.api.request.Request toRequest(
            IRPSValueBase[] rpsValues,
            UUID requestGuid,
            UUID rightsContextGuid,
            UUID processingContextGuid,
            ch.regdata.rps.engine.client.model.api.context.Context loggingContext,
            UUID secretsManager) {
        ch.regdata.rps.engine.client.model.api.request.Request request = new ch.regdata.rps.engine.client.model.api.request.Request();
        request.setGuid(requestGuid);
        request.setRightsContext(rightsContextGuid);
        request.setProcessingContext(processingContextGuid);
        request.setLoggingContext(loggingContext);
        request.setSecretsManager(secretsManager);

        for (IRPSValueBase rpsValue : rpsValues) {
            Instance instance = new Instance();
            instance.setValue(rpsValue.getValue());

            request.addInstance(instance);

            RPSMapping mapping = rpsValue.getMapping();
            if (mapping != null) {
                instance.setClassName(mapping.getClassName());
                instance.setPropertyName(mapping.getPropertyName());
            }

            HashMap<String, String> dependencies = rpsValue.getDependencies();
            if (dependencies != null && dependencies.size() > 0) {
                ch.regdata.rps.engine.client.model.api.context.Context context =
                        ContextExtensions.ToContext(rpsValue.getDependencies(), null);
                instance.setDependencyContext(context);
            }
        }

        return request;
    }

    /**
     * Transforms an API.Context to a Model.Context
     *
     * @param context
     * @param modelContext
     */
    private static void ToModel(Context context,
                                ch.regdata.rps.engine.client.model.api.context.Context modelContext) {
        if (context != null && modelContext != null) {

            List<Evidence> ctxEvidences = context.getEvidences();

            List<ch.regdata.rps.engine.client.model.api.context.Evidence> modelEvidences = new ArrayList<>();

            for (Evidence evidence : ctxEvidences) {
                ch.regdata.rps.engine.client.model.api.context.Evidence modelEvidence = ToModel(evidence);
                modelEvidences.add(modelEvidence);
            }
            modelContext.setEvidences(modelEvidences);
        }
    }

    /**
     * Transforms an API.Evidence to a Model.Evidence
     *
     * @param evidence
     * @return
     */
    private static ch.regdata.rps.engine.client.model.api.context.Evidence ToModel(Evidence evidence) {
        ch.regdata.rps.engine.client.model.api.context.Evidence newEvidence = new ch.regdata.rps.engine.client.model.api.context.Evidence();
        newEvidence.setName(evidence.getName());
        newEvidence.setValue(evidence.getValue());
        return newEvidence;
    }

    /**
     * Transforms a RequestContext to a RequestBody for sending http requests
     *
     * @param requestContext
     * @return
     */
    public RequestBody toRequestBody(RequestContext requestContext) {
        RequestBody requestBody = new RequestBody();

        Enumeration<Request> requests = requestContext.getRequests();
        if (!requests.hasMoreElements())
            return requestBody;

        HashMap<ch.regdata.rps.engine.client.model.api.context.Context, UUID> rightsContextGuids =
                new HashMap<ch.regdata.rps.engine.client.model.api.context.Context, UUID>();
        HashMap<ch.regdata.rps.engine.client.model.api.context.Context, UUID> processingContextGuids =
                new HashMap<ch.regdata.rps.engine.client.model.api.context.Context, UUID>();

        while (requests.hasMoreElements()) {
            Request request = requests.nextElement();
            if (request.getValues() == null || request.getValues().length == 0)
                continue;

            ch.regdata.rps.engine.client.model.api.context.Context rightsContext = new ch.regdata.rps.engine.client.model.api.context.Context();
            ToModel(request.getRightContext(), rightsContext);

            UUID rightsContextGuid = rightsContextGuids.get(rightsContext);
            if (rightsContextGuid == null) {
                rightsContextGuid = UUID.randomUUID();
                rightsContext.setGuid(rightsContextGuid);
                rightsContextGuids.put(rightsContext, rightsContextGuid);

                requestBody.getRightsContexts().add(rightsContext);
            }

            ch.regdata.rps.engine.client.model.api.context.Context processingContext = new ch.regdata.rps.engine.client.model.api.context.Context();
            ToModel(request.getProcessingContext(), processingContext);

            ch.regdata.rps.engine.client.model.api.context.Context loggingContext = null;
            if (request.getLoggingContext() != null) {
                loggingContext = new ch.regdata.rps.engine.client.model.api.context.Context();
                ToModel(request.getLoggingContext(), loggingContext);
            }

            if (processingContext == null) {
                requestBody.getRequests().add(toRequest(request.getValues(),
                        request.getGuid(),
                        rightsContextGuid,
                        null,
                        loggingContext,
                        request.getSecretsManager()));
                continue;
            }

            UUID processingContextGuid = processingContextGuids.get(processingContext);
            if (processingContextGuid == null) {
                processingContextGuid = UUID.randomUUID();
                processingContext.setGuid(processingContextGuid);
                processingContextGuids.put(processingContext, processingContextGuid);

                requestBody.getProcessingContexts().add(processingContext);
            }

            ch.regdata.rps.engine.client.model.api.request.Request toRequest = toRequest(
                    request.getValues(),
                    request.getGuid(),
                    rightsContextGuid,
                    processingContextGuid,
                    loggingContext,
                    request.getSecretsManager());
            requestBody.getRequests().add(toRequest);
        }

        return requestBody;
    }

    /**
     * Walk through the elements of a response body and populate the request context with the value of the respone
     *
     * @param responseBody
     * @param requestContext
     */
    public void fromResponseBody(ResponseBody responseBody, RequestContext requestContext) {
        if (responseBody != null && requestContext != null) {
            if (responseBody.getError() != null)
                throw new RPSEngineException("Error received from RPS Engine API response", responseBody.getError());

            for (Response response : responseBody.getResponses()) {
                Request request = requestContext.tryGetRequest(response.getRequest());
                if (request == null)
                    continue;

                for (int i = 0; i < response.getInstances().size(); i++) {
                    IRPSValueBase value = request.getValues()[i];
                    Instance instance = response.getInstances().get(i);
                    if (instance.getError() != null) {
                        value.setValue(null);
                        value.setError(instance.getError());
                    } else {
                        value.setValue(instance.getValue());
                    }
                }
            }
        }
    }
}
