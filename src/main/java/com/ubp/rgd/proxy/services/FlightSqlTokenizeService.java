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
 * {@code transform_search(...)} takes exactly the same arguments and obeys exactly the same rules,
 * but produces a <b>{@code LIKE} pattern</b> rather than the token: the token is truncated to its
 * first {@value #SEARCH_PREFIX_LENGTH} characters and a {@code %} is appended, so that a value whose
 * tokenization is only stable on its prefix can still be searched for:
 *
 * <pre>
 * SELECT * FROM PERSON WHERE FIRST_NAME LIKE transform_search('Person', 'shortString', 'CH', 'Jean')
 * </pre>
 *
 * becomes
 *
 * <pre>
 * SELECT * FROM PERSON WHERE FIRST_NAME LIKE 'RG{AB1234%'
 * </pre>
 *
 * <b>A {@code %} or a {@code _} occurring inside the token itself is escaped</b> with a backslash,
 * and the pattern is then followed by an {@code ESCAPE '\'} clause, so that a token carrying one is
 * searched for literally rather than as a wildcard. The clause is appended <b>only</b> when the
 * prefix really holds one: it is valid in a {@code LIKE} predicate and nowhere else, so a call
 * written anywhere else keeps working as long as its token is free of wildcards. An RPS token always
 * is; a format-preserving token may not be.
 * <p>
 * All the calls of a statement, of both functions, are transformed in a <b>single</b> engine call,
 * and the contexts come from the {@code before} phase of the mapping configuration.
 */
@ApplicationScoped
public class FlightSqlTokenizeService {

    private static final Logger LOG = LoggerFactory.getLogger(FlightSqlTokenizeService.class);

    /** The name of the extension, matched case-insensitively. */
    static final String FUNCTION_NAME = "transform";

    /** The name of the search variant, matched case-insensitively. */
    static final String SEARCH_FUNCTION_NAME = "transform_search";

    /** How many characters of the token the search variant keeps before the wildcard. */
    static final int SEARCH_PREFIX_LENGTH = 9;

    /** The {@code LIKE} wildcard appended by the search variant, and escaped inside its prefix. */
    private static final char LIKE_WILDCARD_ANY = '%';

    /** The single-character {@code LIKE} wildcard, escaped inside the prefix. */
    private static final char LIKE_WILDCARD_ONE = '_';

    /** The character declared by the emitted {@code ESCAPE} clause. */
    private static final char LIKE_ESCAPE_CHARACTER = '\\';

    /**
     * The recognized function names, <b>longest first</b>: the whole-word rule already keeps
     * {@code transform} from matching the start of {@code transform_search(}, but the order makes
     * the behaviour explicit rather than dependent on that subtlety.
     */
    private static final String[] FUNCTION_NAMES = {SEARCH_FUNCTION_NAME, FUNCTION_NAME};

    /** How both functions are named together, for the messages that concern the extension itself. */
    private static final String EXTENSION_NAMES = FUNCTION_NAME + "()/" + SEARCH_FUNCTION_NAME + "()";

    /** Writing a value into a query can only mean tokenizing it. */
    private static final String DEFAULT_ACTION = "Protect";

    private static final int ARGUMENT_COUNT = 4;

    @Inject
    EndPointTransformer transformer;

    @Inject
    FlightSqlMappingConfigProvider mappingConfigProvider;

    /**
     * Replace every {@code transform(...)} and {@code transform_search(...)} call of a statement:
     * the former by the token of its transformed text, the latter by a {@code LIKE} pattern made of
     * the first {@value #SEARCH_PREFIX_LENGTH} characters of that token.
     * <p>
     * A statement that uses neither is returned as it is, without ever reaching the engine, whatever
     * the {@code before} phase says.
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
                    "The query uses %s but the 'before' phase of the Flight SQL mapping"
                            + " configuration is not active.", EXTENSION_NAMES));
        }

        RPSValue[] values = new RPSValue[calls.size()];
        for (int i = 0; i < calls.size(); i++) {
            FunctionCall call = calls.get(i);
            values[i] = new RPSValue(new RPSMapping(call.className(), call.propertyName()), call.text());
        }

        LOG.debug("Transforming {} extension call(s) of the statement", values.length);
        transformValues(values);

        // Right to left, so that the offsets of the calls still to replace stay valid.
        StringBuilder rewritten = new StringBuilder(sql);
        for (int i = calls.size() - 1; i >= 0; i--) {
            FunctionCall call = calls.get(i);
            String transformed = values[i].getTransformed();
            rewritten.replace(call.start(), call.end(),
                    call.isSearch() ? toSqlLikePattern(transformed) : toSqlLiteral(transformed));
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
            LOG.error("Failed to transform the extension calls of the statement", e);
            throw new RPSTransformException(e);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Scanning
    // ---------------------------------------------------------------------------------------------

    /**
     * Locate every {@code transform(...)} and {@code transform_search(...)} call of a statement, in
     * the order they appear.
     * <p>
     * The statement is walked once, skipping what is not code: a call sitting inside a string
     * literal, a quoted identifier or a comment is left alone, since rewriting it would corrupt the
     * query.
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
            String name = matchFunctionName(sql, index);
            if (name != null) {
                int afterName = index + name.length();
                int parenthesis = skipWhitespace(sql, afterName);
                if (parenthesis < sql.length() && sql.charAt(parenthesis) == '(') {
                    calls.add(readCall(sql, index, parenthesis, name));
                    index = calls.get(calls.size() - 1).end();
                    continue;
                }
                // A column or an alias that merely happens to be named after the extension.
                index = afterName;
                continue;
            }
            index++;
        }
        return calls;
    }

    /**
     * @return the recognized function name starting at {@code index}, or null when none does
     */
    private static String matchFunctionName(String sql, int index) {
        for (String name : FUNCTION_NAMES) {
            if (startsFunctionName(sql, index, name)) {
                return name;
            }
        }
        return null;
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
     * Whether {@code name} starts at {@code index}, as a whole word: {@code my_transform(...)} and
     * {@code transformed} are not calls of the extension.
     */
    private static boolean startsFunctionName(String sql, int index, String name) {
        if (!sql.regionMatches(true, index, name, 0, name.length())) {
            return false;
        }
        if (index > 0 && isNamePart(sql.charAt(index - 1))) {
            return false;
        }
        int after = index + name.length();
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
     * @param nameStart the index of the first character of the function name
     * @param parenthesis the index of the opening parenthesis
     * @param name the recognized function name, as written in the statement
     */
    private static FunctionCall readCall(String sql, int nameStart, int parenthesis, String name) {
        List<String> arguments = new ArrayList<>();
        int index = parenthesis + 1;

        while (true) {
            index = skipWhitespace(sql, index);
            if (index >= sql.length()) {
                throw new FlightSqlTransformSyntaxException(unterminated(sql, nameStart, name));
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
                        arguments.size() + 1, name, peek(sql, index)));
            }
            int literalEnd = skipQuoted(sql, index, '\'');
            if (literalEnd > sql.length() || sql.charAt(literalEnd - 1) != '\'' || literalEnd == index + 1) {
                throw new FlightSqlTransformSyntaxException(unterminated(sql, nameStart, name));
            }
            arguments.add(sql.substring(index + 1, literalEnd - 1).replace("''", "'"));

            index = skipWhitespace(sql, literalEnd);
            if (index >= sql.length()) {
                throw new FlightSqlTransformSyntaxException(unterminated(sql, nameStart, name));
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
                    name, peek(sql, index)));
        }

        if (arguments.size() != ARGUMENT_COUNT) {
            throw new FlightSqlTransformSyntaxException(String.format(
                    "%s() takes %d arguments (className, propertyName, jurisdiction, text) but %d"
                            + " were given in \"%s\".",
                    name, ARGUMENT_COUNT, arguments.size(), sql.substring(nameStart, index)));
        }
        String className = arguments.get(0);
        String propertyName = arguments.get(1);
        if (className.isBlank() || propertyName.isBlank()) {
            throw new FlightSqlTransformSyntaxException(String.format(
                    "The className and the propertyName of %s() cannot be empty, in \"%s\".",
                    name, sql.substring(nameStart, index)));
        }
        // arguments.get(2) is the jurisdiction: read and validated, but not used yet.
        return new FunctionCall(nameStart, index, name, className, propertyName,
                arguments.get(2), arguments.get(3));
    }

    private static String unterminated(String sql, int nameStart, String name) {
        return String.format("Unterminated %s() call in \"%s\".", name, peek(sql, nameStart));
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

    /**
     * Turn a token into the {@code LIKE} pattern of the search variant: its first
     * {@value #SEARCH_PREFIX_LENGTH} characters followed by a {@code %}. A shorter token is kept
     * whole.
     * <p>
     * A {@code %} or a {@code _} of the token itself would act as a wildcard and widen the search,
     * so it is escaped and the pattern is followed by an {@code ESCAPE} clause. The clause is only
     * appended when the prefix really holds one, since it is valid in a {@code LIKE} predicate and
     * nowhere else.
     *
     * @param token the transformed value, possibly null
     * @return the quoted pattern, or {@code NULL} when the engine returned no value
     */
    static String toSqlLikePattern(String token) {
        if (token == null) {
            return "NULL";
        }
        String prefix = token.length() <= SEARCH_PREFIX_LENGTH
                ? token
                : token.substring(0, SEARCH_PREFIX_LENGTH);
        if (!holdsWildcard(prefix)) {
            return toSqlLiteral(prefix + "%");
        }
        StringBuilder escaped = new StringBuilder(prefix.length() + 4);
        for (int i = 0; i < prefix.length(); i++) {
            char current = prefix.charAt(i);
            if (current == LIKE_WILDCARD_ANY || current == LIKE_WILDCARD_ONE
                    || current == LIKE_ESCAPE_CHARACTER) {
                escaped.append(LIKE_ESCAPE_CHARACTER);
            }
            escaped.append(current);
        }
        return toSqlLiteral(escaped.append(LIKE_WILDCARD_ANY).toString())
                + " ESCAPE " + toSqlLiteral(String.valueOf(LIKE_ESCAPE_CHARACTER));
    }

    /**
     * Whether the prefix holds a character that {@code LIKE} would read as a wildcard. The escape
     * character alone does not count: without the clause it is an ordinary character, and escaping
     * it would change what the pattern matches.
     */
    private static boolean holdsWildcard(String prefix) {
        return prefix.indexOf(LIKE_WILDCARD_ANY) >= 0 || prefix.indexOf(LIKE_WILDCARD_ONE) >= 0;
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
     * One located call of the extension.
     *
     * @param start the index of the first character of the call in the statement
     * @param end the index just after its closing parenthesis
     * @param functionName the name as written in the statement, used by the error messages and to
     *        decide how the token is written back
     * @param jurisdiction read and validated, but not used yet
     */
    record FunctionCall(int start, int end, String functionName, String className,
                        String propertyName, String jurisdiction, String text) {

        /** Whether the call is a {@code transform_search()}, producing a {@code LIKE} pattern. */
        boolean isSearch() {
            return SEARCH_FUNCTION_NAME.equalsIgnoreCase(functionName);
        }
    }

    /** A call of the extension the proxy cannot make sense of. */
    public static class FlightSqlTransformSyntaxException extends IllegalArgumentException {
        public FlightSqlTransformSyntaxException(String message) {
            super(message);
        }
    }
}
