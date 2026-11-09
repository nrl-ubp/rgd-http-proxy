package com.ubp.rgd.proxy.sqlgateway;

import com.ubp.rgd.proxy.sqlgateway.config.DataMapping;
import com.ubp.rgd.proxy.sqlgateway.config.MappingConfig;
import com.ubp.rgd.proxy.transform.EndPointTransformer;
import com.ubp.rgd.proxy.transform.model.MappedValue;
import com.ubp.rgd.proxy.transform.model.ValueMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Locates the parts of a value returned by the database that must be detokenized, and rebuilds the
 * value from their clear values. Protocol neutral: the Flight SQL server and the TDS gateway both
 * detokenize their results with it, batching the located segments into a single transformer call.
 * <p>
 * The RPS class / property of a segment is resolved, in this order:
 * <ol>
 *     <li>the <b>column mapping</b> of its column, which applies to every token of the value;</li>
 *     <li>the <b>data mappings</b>, whose regexes locate their own segments in the value;</li>
 *     <li>the <b>token mapping index</b> the token carries, RPS only, see {@link TokenIndexResolver}.</li>
 * </ol>
 * A transformer keeping the value types (FPE) also protects dates and numbers without wrapper: such a
 * typed value is detokenized as a whole, see {@link #typedMapping}.
 */
public final class ValueDetokenizer {

    private static final Logger LOG = LoggerFactory.getLogger(ValueDetokenizer.class);

    private ValueDetokenizer() {
    }

    /**
     * Locate the segments to detokenize in a text value.
     *
     * @param value the value to scan
     * @param columnMapping the mapping of its column, {@code null} to detokenize it implicitly
     * @param config the mapping configuration, giving the data mappings
     * @param transformer the transformer, telling what its tokens look like
     * @return the segments, ordered by position, empty when there is nothing to detokenize
     */
    public static List<Segment> segmentsOf(String value, ValueMapping columnMapping, MappingConfig config,
                                           EndPointTransformer transformer) {
        return columnMapping != null
                ? extractTokens(value, columnMapping, transformer.tokenPattern())
                : extractImplicitSegments(value, config.getUsableDataMappings(), transformer.tokenPattern(),
                        transformer.supportsTokenMappingIndex());
    }

    /**
     * The mapping of a typed value (date or number), which can only be detokenized as a whole: the
     * column mapping, or else the first data mapping matching the whole value.
     *
     * @param text the value in its text form ({@code yyyy-mm-dd} for a date)
     * @param columnMapping the mapping of its column, may be {@code null}
     * @param config the mapping configuration, giving the data mappings
     * @return the mapping, or {@code null} when the value is not to be detokenized
     */
    public static ValueMapping typedMapping(String text, ValueMapping columnMapping, MappingConfig config) {
        return columnMapping != null ? columnMapping : resolveTypedMapping(text, config.getUsableDataMappings());
    }

    /**
     * Rebuild a value from the transformed values of its segments. A segment the transformer left
     * without value keeps its original text.
     *
     * @param original the original value
     * @param segments its segments, transformed
     * @return the clear value
     */
    public static String clearValue(String original, List<Segment> segments) {
        List<String> clearValues = new ArrayList<>(segments.size());
        for (Segment segment : segments) {
            MappedValue rpsValue = segment.rpsValue();
            String transformed = rpsValue.getTransformed();
            if (transformed == null) {
                LOG.error("Detokenization did not return a value for a segment of property {}.{}",
                        rpsValue.getMapping().getClassName(), rpsValue.getMapping().getPropertyName());
                transformed = rpsValue.getOriginal();
            }
            clearValues.add(transformed);
        }
        return rebuild(original, segments, clearValues);
    }

    /**
     * Resolve the mapping of a typed value in implicit mode.
     *
     * @param text         the value in its text form
     * @param dataMappings the usable data mappings, in declaration order
     * @return the mapping of the first data mapping whose regex matches the whole value, or
     *         {@code null} when none does
     */
    public static ValueMapping resolveTypedMapping(String text, List<DataMapping> dataMappings) {
        for (DataMapping dataMapping : dataMappings) {
            if (dataMapping.getPattern().matcher(text).matches()) {
                return new ValueMapping(dataMapping.getRpsClassName(), dataMapping.getRpsPropertyName());
            }
        }
        return null;
    }

    /**
     * Locate the {@code RG{...}} tokens of a value, all mapped to the same RPS class / property. Used
     * for the columns having an explicit {@link ColumnMapping}.
     *
     * @param tokenPattern what a token looks like for the selected transformer
     * @return the segments to detokenize, in order of appearance (empty when the value holds no token)
     */
    public static List<Segment> extractTokens(String value, ValueMapping mapping, Pattern tokenPattern) {
        List<Segment> segments = new ArrayList<>();
        Matcher matcher = tokenPattern.matcher(value);
        while (matcher.find()) {
            segments.add(new Segment(matcher.start(), matcher.end(),
                    new MappedValue(mapping, matcher.group())));
        }
        return segments;
    }

    /**
     * Locate the segments of a value in implicit mode, i.e. for the columns without an explicit
     * {@link ColumnMapping}. The data mappings are applied first, then the tokens they left
     * unclaimed are resolved from the mapping index they carry.
     *
     * @param value the value to scan
     * @param dataMappings the usable data mappings, in declaration order
     * @param tokenPattern what a token looks like for the selected transformer
     * @param useMappingIndex whether the tokens carry a mapping index that can be read
     * @return the segments to detokenize, ordered by position (empty when nothing was resolved)
     */
    public static List<Segment> extractImplicitSegments(String value, List<DataMapping> dataMappings,
                                                 Pattern tokenPattern, boolean useMappingIndex) {
        List<Segment> segments = new ArrayList<>(extractDataMappedSegments(value, dataMappings));
        if (useMappingIndex) {
            segments.addAll(extractIndexMappedSegments(value, segments, tokenPattern));
        }
        segments.sort(Comparator.comparingInt(Segment::start));
        return segments;
    }

    /**
     * Locate the {@code RG{...}} tokens of a value whose RPS class / property is resolved from the
     * mapping index they carry â€” the last resort, applied to the tokens that neither a column mapping
     * nor a data mapping could resolve.
     *
     * @param value the value to scan
     * @param claimed the segments already located by the data mappings; an overlapping token is skipped
     * @param tokenPattern what a token looks like for the selected transformer
     * @return the resolved segments (empty when no token carries a declared mapping index)
     * @see TokenIndexResolver
     */
    public static List<Segment> extractIndexMappedSegments(String value, List<Segment> claimed,
                                                    Pattern tokenPattern) {
        List<Segment> segments = new ArrayList<>();
        Matcher matcher = tokenPattern.matcher(value);
        while (matcher.find()) {
            Segment candidate = new Segment(matcher.start(), matcher.end(), null);
            if (claimed.stream().anyMatch(candidate::overlaps)) {
                continue;
            }
            String token = matcher.group();
            ValueMapping mapping = TokenIndexResolver.resolveMapping(token);
            if (mapping == null) {
                continue;
            }
            segments.add(new Segment(matcher.start(), matcher.end(), new MappedValue(mapping, token)));
        }
        return segments;
    }

    /**
     * Locate the segments of a value using the data mappings â€” the implicit detokenization applied to
     * the columns without an explicit {@link ColumnMapping}.
     * <p>
     * Every mapping is applied in declaration order, which is therefore its priority: a match
     * overlapping a segment already kept by an earlier mapping is discarded. The segment handed to the
     * engine is the whole match, not a capturing group.
     *
     * @param value the value to scan
     * @param dataMappings the usable data mappings, in declaration order
     * @return the kept segments ordered by position (empty when nothing matched)
     */
    public static List<Segment> extractDataMappedSegments(String value,
                                                   List<DataMapping> dataMappings) {
        List<Segment> segments = new ArrayList<>();
        for (DataMapping dataMapping : dataMappings) {
            ValueMapping rpsMapping =
                    new ValueMapping(dataMapping.getRpsClassName(), dataMapping.getRpsPropertyName());
            Matcher matcher = dataMapping.getPattern().matcher(value);
            while (matcher.find()) {
                if (matcher.end() == matcher.start()) {
                    // Guard against a regex able to match an empty string, which would never advance.
                    continue;
                }
                Segment candidate = new Segment(matcher.start(), matcher.end(),
                        new MappedValue(rpsMapping, matcher.group()));
                if (segments.stream().noneMatch(candidate::overlaps)) {
                    segments.add(candidate);
                }
            }
        }
        segments.sort(Comparator.comparingInt(Segment::start));
        return segments;
    }

    /**
     * Rebuild a value by replacing each of its segments with the corresponding clear value, preserving
     * everything surrounding them.
     *
     * @param original the original value
     * @param segments the segments located in that value, ordered by position and non-overlapping
     * @param clearValues the clear value of each segment, in the same order
     */
    public static String rebuild(String original, List<Segment> segments, List<String> clearValues) {
        StringBuilder rebuilt = new StringBuilder();
        int cursor = 0;
        for (int i = 0; i < segments.size() && i < clearValues.size(); i++) {
            Segment segment = segments.get(i);
            rebuilt.append(original, cursor, segment.start()).append(clearValues.get(i));
            cursor = segment.end();
        }
        rebuilt.append(original, cursor, original.length());
        return rebuilt.toString();
    }

    /**
     * A part of a value to detokenize, located by its half-open {@code [start, end)} range in the
     * original value and carrying its own RPS mapping.
     */
    public record Segment(int start, int end, MappedValue rpsValue) {

        public boolean overlaps(Segment other) {
            return start < other.end && other.start < end;
        }
    }
}
