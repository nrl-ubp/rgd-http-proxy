package ch.regdata.rps.engine.client.model.api.response;

import ch.regdata.rps.engine.client.model.api.error.Error;

import java.util.ArrayList;
import java.util.List;

public class ResponseBody {

    private List<Response> responses = new ArrayList<Response>();
    private Error error;

    public ResponseBody() {
    }

    public ResponseBody(Error error) {
        this.error = error;
    }

    public ResponseBody(List<Response> responses, Error error) {
        this.responses = responses;
        this.error = error;
    }

    public static ResponseBody WithError(String code) {
        return WithError(code, null);
    }

    public static ResponseBody WithError(String code, String message) {
        return WithError(new Error(code, message));
    }

    public static ResponseBody WithError(Error error) {
        return new ResponseBody(null, error);
    }

    public List<Response> getResponses() {
        return responses;
    }

    public void setResponses(List<Response> responses) {
        this.responses = responses;
    }

    public Error getError() {
        return error;
    }

    public void setError(Error error) {
        this.error = error;
    }

    public void addResponse(Response response) {
        responses.add(response);
    }
}
