package com.ubp.rgd.proxy.transform;

import ch.regdata.rps.engine.client.model.api.value.RPSValue;

/**
 * A single value read from a JSON payload, along with the information needed to write the
 * transformed value back with its original JSON type.
 * <p>
 * RPS works on text, but a JSON number must stay a JSON number once tokenized: the engine returns
 * a number when it tokenizes a number. Remembering that the value was read as a number is what
 * allows {@code 42} to be written back as {@code 8371} rather than {@code "8371"}.
 *
 * @param rpsValue the value handed over to the RPS engine, holding both the original and the
 *                 transformed text
 * @param numeric  {@code true} when the value was read as a JSON number
 */
public record JsonPathValue(RPSValue rpsValue, boolean numeric) {
}
