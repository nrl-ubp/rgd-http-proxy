package com.ubp.rgd.proxy.transform.model;

/**
 * A value to transform with its mapping. The transformer fills in the transformed value or, when the
 * value could not be transformed, an error message.
 */
public class MappedValue {

    private final ValueMapping mapping;
    private final String original;
    private String transformed;
    private String error;

    public MappedValue(ValueMapping mapping, String original) {
        this.mapping = mapping;
        this.original = original;
    }

    public ValueMapping getMapping() {
        return mapping;
    }

    public String getOriginal() {
        return original;
    }

    public String getTransformed() {
        return transformed;
    }

    public void setTransformed(String transformed) {
        this.transformed = transformed;
    }

    /**
     * @return why the value could not be transformed, {@code null} when it was
     */
    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    @Override
    public String toString() {
        // the values themselves are sensitive, they must not end up in the logs
        return "MappedValue{" + mapping + (error == null ? "" : ", error=" + error) + "}";
    }
}
