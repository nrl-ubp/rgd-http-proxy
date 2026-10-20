package com.ubp.rgd.proxy.transform.model;

import java.util.Objects;

/**
 * The class and property a value belongs to, which select how the tokenizer transforms it.
 */
public final class ValueMapping {

    private final String className;
    private final String propertyName;

    public ValueMapping(String className, String propertyName) {
        this.className = className;
        this.propertyName = propertyName;
    }

    public String getClassName() {
        return className;
    }

    public String getPropertyName() {
        return propertyName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ValueMapping other)) {
            return false;
        }
        return Objects.equals(className, other.className) && Objects.equals(propertyName, other.propertyName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(className, propertyName);
    }

    @Override
    public String toString() {
        return className + "." + propertyName;
    }
}
