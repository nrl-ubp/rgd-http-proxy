package ch.regdata.rps.engine.client.engine;


import ch.regdata.rps.engine.client.model.api.request.RequestBody;
import ch.regdata.rps.engine.client.model.api.response.ResponseBody;

public interface IRPSEngineProvider {
    ResponseBody transformAsync(RequestBody requestBody);
}

