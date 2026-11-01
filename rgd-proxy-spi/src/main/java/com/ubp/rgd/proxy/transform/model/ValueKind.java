package com.ubp.rgd.proxy.transform.model;

/**
 * The type of a value as it was read from its source, so that a transformer able to preserve value
 * types can hand back a value of the same type.
 */
public enum ValueKind {

    /** A textual value, the default. A textual value may still hold a date. */
    TEXT,

    /** A value read as a number, for instance a JSON number or a numeric database column. */
    NUMBER,

    /** A value read as a date, for instance a date database column, in ISO {@code yyyy-mm-dd} form. */
    DATE
}
