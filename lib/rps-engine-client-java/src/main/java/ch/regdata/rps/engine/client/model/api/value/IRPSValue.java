package ch.regdata.rps.engine.client.model.api.value;

public interface IRPSValue<T> extends IRPSValueBase {

    T getOriginal();

    T getTransformed();
}
