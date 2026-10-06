package ch.regdata.rps.engine.client.model.api;

import ch.regdata.rps.engine.client.model.api.context.Context;

public class Instance {

    private Context loggingContext;
    private Context dependencyContext;
    private String className;
    private String propertyName;
    private String value;
    private ValueError error;

    public Context getLoggingContext() {
        return loggingContext;
    }

    public void setLoggingContext(Context loggingContext) {
        this.loggingContext = loggingContext;
    }

    public Context getDependencyContext() {
        return dependencyContext;
    }

    public void setDependencyContext(Context dependencyContext) {
        this.dependencyContext = dependencyContext;
    }

    public String getClassName() {
        return className;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public String getPropertyName() {
        return propertyName;
    }

    public void setPropertyName(String propertyName) {
        this.propertyName = propertyName;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public ValueError getError() {
        return error;
    }

    public void setError(ValueError error) {
        this.error = error;
    }
}
