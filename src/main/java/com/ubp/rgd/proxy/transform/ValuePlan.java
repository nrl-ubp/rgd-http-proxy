package com.ubp.rgd.proxy.transform;

import ch.regdata.rps.engine.client.mapping.RPSMapping;
import ch.regdata.rps.engine.client.model.api.value.RPSValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plan to transform a single value and rebuild it from its transformed segments while preserving
 * the original format (surrounding characters, separators, segment order).
 * <p>
 * A value is either transformed as a whole, or split into segments located by an extraction
 * pattern. Splitting is what allows a value to be tokenized word by word:
 * <pre>
 *     "Jean-Claude DUSSE"  ->  "RG{...}-RG{...} RG{...}"
 * </pre>
 * The separators are never sent to the engine, they are simply kept in place when the value is
 * reassembled. The same mechanism detokenizes such a value: the tokens are located with
 * {@link #TOKEN_PATTERN} and each one is replaced by its clear value, which restores the original
 * formatting.
 * <p>
 * This class is shared by the {@code /transform} endpoint, the HTTP proxy transformer and the file
 * transformer so that they all tokenize and reassemble values the exact same way.
 */
public final class ValuePlan {

    private static final String ACTION_PROTECT = "Protect";
    private static final String ACTION_UNPROTECT = "Unprotect";

    /** Fixed delimiter pattern of an RPS token ({@code RG{...}}), used to locate tokens on unprotect. */
    public static final Pattern TOKEN_PATTERN = Pattern.compile("RG\\{[^}]*\\}");

    private final String originalValue;
    private final Pattern pattern;
    private final List<RPSValue> rpsValues;

    private ValuePlan(String originalValue, Pattern pattern, List<RPSValue> rpsValues) {
        this.originalValue = originalValue;
        this.pattern = pattern;
        this.rpsValues = rpsValues;
    }

    /**
     * Choose the extraction pattern to apply to a value depending on the action:
     * <ul>
     *     <li>Protect: the configured extract regex when present, so the value is tokenized word by
     *         word. Without a regex the value is transformed as a whole.</li>
     *     <li>Unprotect: the fixed {@link #TOKEN_PATTERN}, so every token embedded in the value is
     *         detokenized whatever the formatting around it.</li>
     *     <li>Any other action, or no action at all: {@code null}, the value is transformed as a
     *         whole.</li>
     * </ul>
     *
     * @param action       the RPS action, typically taken from the {@code Action} evidence
     * @param extractRegex the extract regex configured for the value, may be {@code null}
     * @return the pattern locating the segments to transform, or {@code null} for the whole value
     * @throws RPSTransformException when the configured extract regex is not a valid regex
     */
    public static Pattern extractionPattern(String action, String extractRegex) throws RPSTransformException {
        if (ACTION_PROTECT.equalsIgnoreCase(action)) {
            if (extractRegex == null || extractRegex.isEmpty()) {
                return null;
            }
            try {
                return Pattern.compile(extractRegex);
            } catch (Exception e) {
                throw new RPSTransformException("Invalid extract-regex: " + extractRegex);
            }
        }

        // TODO add search action

        if (ACTION_UNPROTECT.equalsIgnoreCase(action) || action == null) {
            return TOKEN_PATTERN;
        }
        return null;
    }

    /**
     * Build the plan for a value: the ordered list of segments to transform and one
     * {@link RPSValue} per segment.
     *
     * @param mapping the RPS class and property the value belongs to
     * @param value   the original value
     * @param pattern the pattern locating the segments, or {@code null} to transform the whole value
     * @return the plan holding the values to hand over to the engine
     */
    public static ValuePlan of(RPSMapping mapping, String value, Pattern pattern) {
        List<RPSValue> rpsValues = new ArrayList<>();
        if (pattern == null) {
            // Whole value transformed as a single segment.
            rpsValues.add(new RPSValue(mapping, value));
        } else {
            Matcher matcher = pattern.matcher(value);
            while (matcher.find()) {
                rpsValues.add(new RPSValue(mapping, matcher.group()));
            }
        }
        return new ValuePlan(value, pattern, rpsValues);
    }

    /**
     * The values to hand over to the RPS engine, one per segment.
     *
     * @return the segments values, empty when the pattern matched nothing
     */
    public List<RPSValue> rpsValues() {
        return Collections.unmodifiableList(rpsValues);
    }

    /**
     * Rebuild the value from its transformed segments, keeping everything the pattern did not
     * match exactly where it was.
     *
     * @return the transformed value, with the formatting of the original one
     * @throws RPSTransformException when a segment was not transformed by the engine
     */
    public String reassemble() throws RPSTransformException {
        if (rpsValues.isEmpty()) {
            // No segment matched (e.g. regex with no match): return the value unchanged.
            return originalValue;
        }
        if (pattern == null) {
            // Single whole-value segment.
            return requireTransformed(rpsValues.getFirst());
        }

        Matcher matcher = pattern.matcher(originalValue);
        StringBuilder rebuilt = new StringBuilder();
        int index = 0;
        while (matcher.find() && index < rpsValues.size()) {
            String transformed = requireTransformed(rpsValues.get(index++));
            matcher.appendReplacement(rebuilt, Matcher.quoteReplacement(transformed));
        }
        matcher.appendTail(rebuilt);
        return rebuilt.toString();
    }

    private String requireTransformed(RPSValue rpsValue) throws RPSTransformException {
        String transformed = rpsValue.getTransformed();
        if (transformed == null) {
            String rpsErrMsg = rpsValue.getError() != null ? rpsValue.getError().getMessage() : "Unknown RPS error message";
            throw new RPSTransformException(
                    String.format("Transformation did not return a value for: %s. Error: %s", rpsValue.getOriginal(), rpsErrMsg));
        }
        return transformed;
    }
}
