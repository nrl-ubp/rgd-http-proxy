package com.ubp.rgd.proxy.services;

import ch.regdata.rps.engine.client.Context;
import ch.regdata.rps.engine.client.Evidence;
import ch.regdata.rps.engine.client.enginecontext.ProcessingContext;
import ch.regdata.rps.engine.client.enginecontext.RightContext;
import ch.regdata.rps.engine.client.mapping.RPSMapping;
import ch.regdata.rps.engine.client.model.api.value.RPSValue;
import com.ubp.rgd.proxy.transform.RPSTransformException;
import com.ubp.rgd.proxy.transform.EndPointTransformer;
import com.ubp.rgd.proxy.transform.config.FlightSqlPhaseConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The {@code transform()} SQL extension, applied to a statement <b>before</b> it is sent to the
 * database server.
 * <p>
 * A client that holds a clear value but queries a tokenized column cannot compare the two directly.
 * It wraps the value in a {@code transform(...)} call, and this service replaces the call by the
 * token the engine returns:
 *
 * <pre>
 * SELECT * FROM PERSON WHERE FIRST_NAME = transform('Person', 'shortString', 'CH', 'Jean-Claude')
 * </pre>
 *
 * becomes
 *
 * <pre>
 * SELECT * FROM PERSON WHERE FIRST_NAME = 'RG{AB12345678aa}'
 * </pre>
 *
 * The arguments are, in order, the RPS class name, the RPS property name, the jurisdiction and the
 * text to transform. <b>The jurisdiction is parsed and validated but deliberately ignored</b> for
 * now; it is a later step, not an oversight.
 * <p>
 * All the calls of a statement are transformed in a <b>single</b> engine call, and the contexts come
 * from the {@code before} phase of the mapping configuration.
 */
@ApplicationScoped
public class FlightSqlTokenizeService {

    private static final Logger LOG = LoggerFactory.getLogger(FlightSqlTokenizeService.class);

    /** The name of the extension, matched case-insensitively. */
    static final String FUNCTION_NAME = "transform";

    /** Writing a value into a query can only mean tokenizing it. */
    private static final String DEFAULT_ACTION = "Protect";

    private static final int ARGUMENT_COUNT = 4;

    @Inject
    EndPointTransformer transformer;

    @Inject
    FlightSqlMappingConfigProvider mappingConfigProvider;

    /**
     * Replace every {@code transform(...)} call of a statement by the token of its transformed text.
     * <p>
     * A statement that uses no {@code transform()} is returned as it is, without ever reaching the
     * engine, whatever the {@code before} phase says.
     *
     * @param sql the statement as the client sent it
     * @return the statement to send to the database server
     * @throws FlightSqlTransformSyntaxException when a call is malformed, or when the {@code before}
     *         phase is not active
     * @throws RPSTransformException when the engine fails to transform the values
     */
    public String rewrite(String sql) throws RPSTransformException {
        if (sql == null || sql.isEmpty()) {
            return sql;
        }
        List<FunctionCall> calls = findCalls(sql);
        if (calls.isEmpty()) {
            return sql;
        }

        if (!beforeActive()) {
            // Leaving the call in place would only produce an obscure syntax error from the database,
            // and substituting the clear text would send unprotected data to it.
            throw new FlightSqlTransformSyntaxException(String.format(
                    "The query uses %s() but the 'before' phase of the Flight SQL mapping"
                            + " configuration is not active.", FUNCTION_NAME));
        }

        RPSValue[] values = new RPSValue[calls.size()];
        for (int i = 0; i < calls.size(); i++) {
            FunctionCall call = calls.get(i);
            values[i] = new RPSValue(new RPSMapping(call.className(), call.propertyName()), call.text());
        }

        LOG.debug("Transforming {} {}() call(s) of the statement", values.length, FUNCTION_NAME);
        transformValues(values);

        // Right to left, so that the offsets of the calls still to replace stay valid.
        StringBuilder rewritten = new StringBuilder(sql);
        for (int i = calls.size() - 1; i >= 0; i--) {
            FunctionCall call = calls.get(i);
            rewritten.replace(call.start(), call.end(), toSqlLiteral(values[i].getTransformed()));
        }
        return rewritten.toString();
    }

    /**
     * Hand the values over to the engine, which replaces their transformed value in place. Extracted
     * so that it can be substituted in the tests.
     *
     * @param values one value per {@code transform()} call of the statement
     */
    protected void transformValues(RPSValue[] values) throws RPSTransformException {
        try {
            transformer.transformData(values, buildRightContext(), buildProcessingContext());
        } catch (Exception e) {
            LOG.error("Failed to transform the {}() calls of the statement", FUNCTION_NAME, e);
            throw new RPSTransformException(e);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Scanning
    // ---------------------------------------------------------------------------------------------

    /**
     * Locate every {@code transform(...)} call of a statement, in the order they appear.
     * <p>
     * The statement is walked once, skipping what is not code: a {@code transform(} sitting inside a
     * string literal, a quoted identifier or a comment is left alone, since rewriting it would corrupt
     * the query.
     *
     * @param sql the statement to scan
     * @return the located calls with their arguments and their position in {@code sql}
     * @throws FlightSqlTransformSyntaxException when a call is malformed
     */
    static List<FunctionCall> findCalls(String sql) {
        List<FunctionCall> calls = new ArrayList<>();
        int index = 0;
        while (index < sql.length()) {
            int skipped = skipNonCode(sql, index);
            if (skipped > index) {
                index = skipped;
                continue;
            }
            if (startsFunctionName(sql, index)) {
                int afterName = index + FUNCTION_NAME.length();
                int parenthesis = skipWhitespace(sql, afterName);
                if (parenthesis < sql.length() && sql.charAt(parenthesis) == '(') {
                    calls.add(readCall(sql, index, parenthesis));
                    index = calls.get(calls.size() - 1).end();
                    continue;
                }
                // A column or an alias that merely happens to be named "transform".
                index = afterName;
                continue;
            }
            index++;
        }
        return calls;
    }

    /**
     * @return the index just after the literal, quoted identifier or comment starting at
     *         {@code index}, or {@code index} itself when none starts there
     */
    private static int skipNonCode(String sql, int index) {
        char current = sql.charAt(index);
        if (current == '\'' || current == '"') {
            return skipQuoted(sql, index, current);
        }
        if (current == '-' && index + 1 < sql.length() && sql.charAt(index + 1) == '-') {
            int end = sql.indexOf('\n', index);
            return end < 0 ? sql.length() : end + 1;
        }
        if (current == '/' && index + 1 < sql.length() && sql.charAt(index + 1) == '*') {
            int end = sql.indexOf("*/", index + 2);
            return end < 0 ? sql.length() : end + 2;
        }
        return index;
    }

    /**
     * Skip a quoted run, the quote character doubled inside it being an escaped quote.
     *
     * @return the index just after the closing quote, or the end of the statement when it is missing
     */
    private static int skipQuoted(String sql, int start, char quote) {
        int index = start + 1;
        while (index < sql.length()) {
            if (sql.charAt(index) == quote) {
                if (index + 1 < sql.length() && sql.charAt(index + 1) == quote) {
                    index += 2;
                    continue;
                }
                return index + 1;
            }
            index++;
        }
        return sql.length();
    }

    /**
     * Whether the function name starts at {@code index}, as a whole word: {@code my_transform(...)}
     * and {@code transformed} are not calls of the extension.
     */
    private static boolean startsFunctionName(String sql, int index) {
        if (!sql.regionMatches(true, index, FUNCTION_NAME, 0, FUNCTION_NAME.length())) {
            return false;
        }
        if (index > 0 && isNamePart(sql.charAt(index - 1))) {
            return false;
        }
        int after = index + FUNCTION_NAME.length();
        return after >= sql.length() || !isNamePart(sql.charAt(after));
    }

    private static boolean isNamePart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '.';
    }

    private static int skipWhitespace(String sql, int index) {
        int current = index;
        while (current < sql.length() && Character.isWhitespace(sql.charAt(current))) {
            current++;
        }
        return current;
    }

    /**
     * Read one call, from the start of its name to the closing parenthesis.
     *
     * @param nameStart the index of the {@code t} of the function name
     * @param parenthesis the index of the opening parenthesis
     */
    private static FunctionCall readCall(String sql, int nameStart, int parenthesis) {
        List<String> arguments = new ArrayList<>();
        int index = parenthesis + 1;

        while (true) {
            index = skipWhitespace(sql, index);
            if (index >= sql.length()) {
                throw new FlightSqlTransformSyntaxException(unterminated(sql, nameStart));
            }
            if (sql.charAt(index) == ')' && arguments.isEmpty()) {
                index++;
                break;
            }
            if (sql.charAt(index) != '\'') {
                throw new FlightSqlTransformSyntaxException(String.format(
                        "Argument %d of %s() must be a single-quoted text, found \"%s\". A parameter"
                                + " placeholder cannot be used: it has no value yet when the query is"
                                + " rewritten.",
                        arguments.size() + 1, FUNCTION_NAME, peek(sql, index)));
            }
            int literalEnd = skipQuoted(sql, index, '\'');
            if (literalEnd > sql.length() || sql.charAt(literalEnd - 1) != '\'' || literalEnd == index + 1) {
                throw new FlightSqlTransformSyntaxException(unterminated(sql, nameStart));
            }
            arguments.add(sql.substring(index + 1, literalEnd - 1).replace("''", "'"));

            index = skipWhitespace(sql, literalEnd);
            if (index >= sql.length()) {
                throw new FlightSqlTransformSyntaxException(unterminated(sql, nameStart));
            }
            if (sql.charAt(index) == ',') {
                index++;
                continue;
            }
            if (sql.charAt(index) == ')') {
                index++;
                break;
            }
            throw new FlightSqlTransformSyntaxException(String.format(
                    "Expected a comma or a closing parenthesis in %s(), found \"%s\".",
                    FUNCTION_NAME, peek(sql, index)));
        }

        if (arguments.size() != ARGUMENT_COUNT) {
            throw new FlightSqlTransformSyntaxException(String.format(
                    "%s() takes %d arguments (className, propertyName, jurisdiction, text) but %d"
                            + " were given in \"%s\".",
                    FUNCTION_NAME, ARGUMENT_COUNT, arguments.size(), sql.substring(nameStart, index)));
        }
        String className = arguments.get(0);
        String propertyName = arguments.get(1);
        if (className.isBlank() || propertyName.isBlank()) {
            throw new FlightSqlTransformSyntaxException(String.format(
                    "The className and the propertyName of %s() cannot be empty, in \"%s\".",
                    FUNCTION_NAME, sql.substring(nameStart, index)));
        }
        // arguments.get(2) is the jurisdiction: read and validated, but not used yet.
        return new FunctionCall(nameStart, index, className, propertyName,
                arguments.get(2), arguments.get(3));
    }

    private static String unterminated(String sql, int nameStart) {
        return String.format("Unterminated %s() call in \"%s\".",
                FUNCTION_NAME, peek(sql, nameStart));
    }

    private static String peek(String sql, int index) {
        int end = Math.min(sql.length(), index + 30);
        return sql.substring(index, end);
    }

    /** Quote a value as a SQL text literal, since the call sits where a value is expected. */
    static String toSqlLiteral(String value) {
        if (value == null) {
            return "NULL";
        }
        return "'" + value.replace("'", "''") + "'";
    }

    // ---------------------------------------------------------------------------------------------
    // Contexts
    // ---------------------------------------------------------------------------------------------

    private boolean beforeActive() {
        FlightSqlPhaseConfig phase = beforePhase();
        return phase.isActive();
    }

    /**
     * The phase that applies to a statement on its way to the database server.
     *
     * @return the configured {@code before} phase, never null
     */
    private FlightSqlPhaseConfig beforePhase() {
        FlightSqlPhaseConfig phase = mappingConfigProvider.get().getBefore();
        return phase == null ? new FlightSqlPhaseConfig() : phase;
    }

    RightContext buildRightContext() {
        RightContext context = new RightContext();
        addEvidences(context, beforePhase().getRightContextEvidences());
        return context;
    }

    ProcessingContext buildProcessingContext() {
        ProcessingContext context = new ProcessingContext();
        addEvidences(context, beforePhase().getProcessingContextEvidences());

        boolean hasAction = context.getEvidences().stream()
                .anyMatch(evidence -> "Action".equalsIgnoreCase(evidence.getName()));
        if (!hasAction) {
            LOG.warn("The Flight SQL 'before' phase declares no Action evidence. Using {}.",
                    DEFAULT_ACTION);
            context.addEvidence(new Evidence("Action", DEFAULT_ACTION));
        }
        return context;
    }

    private void addEvidences(Context context, Map<String, String> evidences) {
        if (evidences == null) {
            return;
        }
        evidences.forEach((name, value) -> {
            if (name != null && !name.isBlank() && value != null && !value.isEmpty()) {
                context.addEvidence(new Evidence(name, value));
            }
        });
    }

    /** For the tests, which build the service outside of CDI. */
    public void setTransformer(EndPointTransformer transformer) {
        this.transformer = transformer;
    }

    /** For the tests, which build the service outside of CDI. */
    public void setMappingConfigProvider(FlightSqlMappingConfigProvider mappingConfigProvider) {
        this.mappingConfigProvider = mappingConfigProvider;
    }

    /**
     * One located {@code transform(...)} call.
     *
     * @param start the index of the first character of the call in the statement
     * @param end the index just after its closing parenthesis
     * @param jurisdiction read and validated, but not used yet
     */
    record FunctionCall(int start, int end, String className, String propertyName,
                        String jurisdiction, String text) {
    }

    /** A {@code transform()} call the proxy cannot make sense of. */
    public static class FlightSqlTransformSyntaxException extends IllegalArgumentException {
        public FlightSqlTransformSyntaxException(String message) {
            super(message);
        }
    }
}
