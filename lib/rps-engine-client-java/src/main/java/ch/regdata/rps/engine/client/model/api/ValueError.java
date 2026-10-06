package ch.regdata.rps.engine.client.model.api;

import java.util.UUID;

public class ValueError {

    private UUID code;
    private String message;

    public ValueError() {

    }

    public ValueError(UUID guid, String message) {
        this.code = guid;
        this.message = message;
    }

    public ValueError(String guid, String message) {
        this.code = UUID.fromString(guid);
        this.message = message;
    }

    public static ValueError Create(String guid, String message) {
        return new ValueError(guid, message);
    }

    public UUID getCode() {
        return code;
    }

    public void setCode(UUID guid) {
        this.code = guid;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
