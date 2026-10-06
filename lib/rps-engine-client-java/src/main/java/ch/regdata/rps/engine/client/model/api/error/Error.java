package ch.regdata.rps.engine.client.model.api.error;

import java.util.UUID;

public class Error {

    private UUID code;
    private String message;

    public Error(String guid, String message) {
        this.code = UUID.fromString(guid);
        this.message = message;
    }

    public Error(UUID code, String message) {
        this.code = code;
        this.message = message;
    }

    public UUID getCode() {
        return code;
    }

    public void setCode(UUID code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
