package com.ubp.rgd.proxy.transform;

/**
 * A single value read from a JSON payload, along with everything needed to write the transformed
 * value back with its original JSON type and formatting.
 * <p>
 * RPS works on text, but a JSON number must stay a JSON number once tokenized: the engine returns
 * a number when it tokenizes a number. Remembering that the value was read as a number is what
 * allows {@code 42} to be written back as {@code 8371} rather than {@code "8371"}.
 *
 * @param plan    the transformation plan of the value, holding one RPS value per segment and able
 *                to rebuild the value while preserving its original formatting
 * @param numeric {@code true} when the value was read as a JSON number
 */
public record JsonPathValue(ValuePlan plan, boolean numeric) {
}
