package ch.regdata.rps.engine.client.model.api.value;

import ch.regdata.rps.engine.client.mapping.RPSMapping;
import ch.regdata.rps.engine.client.model.api.ValueError;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public abstract class RPSValueBase implements IRPSValueBase {

    private RPSMapping mapping;
    private final HashMap<String, String> dependencies = new HashMap<String, String>();
    private ValueError error;
    private Object target;

    protected RPSValueBase() {

    }

    protected RPSValueBase(RPSMapping mapping) {
        this.mapping = mapping;
    }

    public boolean isEmpty() {
        // Default implementation
        return false;
    }

    public RPSMapping getMapping() {
        return mapping;
    }

    public void setMapping(RPSMapping mapping) {
        this.mapping = mapping;
    }

    public void addDependency(String name, String value) {
        dependencies.put(name, value);
    }

    public void removeDependency(String name) {
        dependencies.remove(name);
    }

    public void addDependencyRange(Iterator<HashMap<String, String>> dependencies) {
        while (dependencies != null && dependencies.hasNext()) {
            Map.Entry<String, String> pair = (Map.Entry<String, String>) dependencies.next();
            addDependency(pair.getKey(), pair.getValue());
        }
    }

    public HashMap<String, String> getDependencies() {
        return dependencies;
    }

    public ValueError getError() {
        return error;
    }

    public void setError(ValueError error) {
        this.error = error;
    }

    public Object getTarget() {
        return target;
    }

    public void setTarget(Object target) {
        this.target = target;
    }
}
