package ch.regdata.rps.engine.client.model.api.value;

import ch.regdata.rps.engine.client.mapping.RPSMapping;
import ch.regdata.rps.engine.client.model.api.ValueError;

import java.util.HashMap;

public interface IRPSValueBase {

    HashMap<String, String> getDependencies();

    RPSMapping getMapping();

    String getValue();

    void setValue(String value);

    void addDependency(String name, String value);

    void removeDependency(String name);

    void setError(ValueError error);

    boolean isEmpty();
}
