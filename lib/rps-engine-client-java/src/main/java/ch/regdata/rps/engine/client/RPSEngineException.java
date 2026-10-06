package ch.regdata.rps.engine.client;

import ch.regdata.rps.engine.client.model.api.error.Error;

public class RPSEngineException extends RuntimeException {

    private Error error;

    public RPSEngineException(String message, Error error) {
        super(message);
        this.error = error;
    }

    public Error getError() {
        return error;
    }

    public void setError(Error error) {
        this.error = error;
    }
}
