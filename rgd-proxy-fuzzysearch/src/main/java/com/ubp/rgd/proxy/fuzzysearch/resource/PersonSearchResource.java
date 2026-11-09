package com.ubp.rgd.proxy.fuzzysearch.resource;

import com.ubp.rgd.proxy.fuzzysearch.FuzzySearch;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import com.ubp.rgd.proxy.fuzzysearch.jpa.FuzzySearchPersistence;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import com.ubp.rgd.proxy.fuzzysearch.dto.PersonResponse;
import com.ubp.rgd.proxy.fuzzysearch.dto.PersonSearchRequest;
import com.ubp.rgd.proxy.fuzzysearch.entity.PersonEntity;
import com.ubp.rgd.proxy.fuzzysearch.service.search.OpenSearchPersonIndex;
import com.ubp.rgd.proxy.fuzzysearch.service.phonetic.PhoneticSignatureService;
import com.ubp.rgd.proxy.fuzzysearch.service.protection.ProtectionService;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * REST endpoint for fuzzy person search.
 *
 * <h2>Search strategy: exact-first, fuzzy fallback</h2>
 *
 * <h3>Pass 1 – Exact match (database)</h3>
 * <p>The values are stored protected as a whole by the transformer of the
 * rgd-http-proxy, which is deterministic per column mapping (FPE and RPS
 * alike). The query is therefore protected the same way, in a few case
 * variants (as typed, Capitalized, lowercase, UPPER), with the mapping of
 * {@code FIRST_NAME} and of {@code LAST_NAME}, and looked up by equality:
 * <ol>
 *   <li>the whole query first, which finds a stored {@code Jean-Claude} or
 *       {@code Le Gall};</li>
 *   <li>then each word, a person having to match every word (in either
 *       field).</li>
 * </ol>
 * A part of a stored value, such as {@code Jean} for {@code Jean-Claude},
 * cannot be found this way: that is the job of pass 2.
 *
 * <h3>Pass 2 – Fuzzy search (OpenSearch, fallback only)</h3>
 * <p>If pass 1 returns nothing, expand each token to its top-{@value #NEIGHBOUR_COUNT}
 * nearest training tokens via the hybrid FastText + Jaro-Winkler search,
 * compute the character 3-gram and 4-gram SHA-256 signatures for each
 * neighbour, and query OpenSearch once per neighbour.  Results are merged in
 * neighbour rank order (closest neighbour first) using a
 * {@link LinkedHashSet} to deduplicate.
 */
@Path(FuzzySearch.BASE_PATH + "/persons")
@FuzzySearchEndpoint
@Tag(name = FuzzySearch.TAG, description = "Fuzzy person search over the protected PERSON table")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class PersonSearchResource {

    private static final Logger LOG = LoggerFactory.getLogger(PersonSearchResource.class);

    /** Number of nearest training neighbours used in the fuzzy fallback. */
    private static final int NEIGHBOUR_COUNT = 5;

    /** Maximum number of persons returned by any search pass. */
    private static final int MAX_RESULTS = 100;

    @Inject
    ProtectionService protectionService;

    @Inject
    PhoneticSignatureService phoneticSignatureService;

    @Inject
    OpenSearchPersonIndex personIndex;

    @Inject
    @FuzzySearchPersistence
    EntityManager em;

    /** The PERSON table has no tenant: every person belongs to this one. */
    @ConfigProperty(name = "fuzzy-search.tenant-id", defaultValue = "default")
    String tenantId;

    /**
     * Loads a single person by ID and unprotects all stored fields.
     *
     * <p>Intended for testing and debugging: compare the returned plain-text
     * values with the clear values loaded through the proxy to verify that the
     * round trip (protect → store → load → unprotect) is correct.
     *
     * @param id the primary key of the person to retrieve
     * @return the unprotected {@link PersonResponse}
     * @throws NotFoundException HTTP 404 if no person with that ID exists
     */
    @GET
    @Path("/{id}")
    @Transactional
    public PersonResponse getById(@PathParam("id") Integer id) {
        PersonEntity entity = em.find(PersonEntity.class, id);
        if (entity == null) {
            throw new NotFoundException("Person not found: id=" + id);
        }
        return toResponses(List.of(entity)).get(0);
    }

    /**
     * Performs a person search with exact-first priority and fuzzy fallback.
     *
     * <h3>Algorithm</h3>
     * <ol>
     *   <li>Tokenise the query via {@link ProtectionService#tokenize(String)}.</li>
     *   <li><strong>Pass 1 – exact match (database)</strong>: protect the query
     *       and its words, look them up by equality, intersect across words.
     *       Return immediately if any persons found.</li>
     *   <li><strong>Pass 2 – fuzzy fallback (OpenSearch)</strong>: expand to
     *       top-{@value #NEIGHBOUR_COUNT} neighbours via hybrid FastText + JW search,
     *       query OpenSearch per neighbour, merge in rank order.</li>
     * </ol>
     *
     * @param req the search request (query string + tenant ID)
     * @return ordered list of matching persons (max {@value #MAX_RESULTS}); empty when nothing found
     */
    @POST
    @Path("/search")
    @Transactional
    public List<PersonResponse> search(PersonSearchRequest req) {

        String tenant = req.tenantId() == null || req.tenantId().isBlank() ? tenantId : req.tenantId();
        if (!tenantId.equals(tenant)) {
            LOG.info("Unknown tenant '" + tenant + "', nothing to search.");
            return List.of();
        }

        // 1. Tokenise the user query
        List<String> tokens = protectionService.tokenize(req.query());
        LOG.debug("Search tokens: " + tokens);
        if (tokens.isEmpty()) {
            return List.of();
        }

        // ── Pass 1: exact match in the database ─────────────────────────────
        List<PersonEntity> exactEntities = exactMatch(req.query());
        if (!exactEntities.isEmpty()) {
            List<PersonEntity> capped = exactEntities.size() > MAX_RESULTS
                    ? exactEntities.subList(0, MAX_RESULTS) : exactEntities;
            LOG.info("Pass 1 (exact) returned " + exactEntities.size()
                    + " person(s) (capped at " + MAX_RESULTS + "). Returning immediately.");
            return toResponses(capped);
        }

        LOG.info("Pass 1 (exact) returned nothing – falling back to fuzzy OpenSearch search.");

        // ── Pass 2: boosted fuzzy search via OpenSearch ─────────────────────
        //
        // For each query token, compute:
        //   - prefix n-gram hashes (boost 4) → "starts with"
        //   - suffix n-gram hashes (boost 3) → "ends with"
        //   - all n-gram hashes    (boost 2) → "contains"
        //   - neighbour n-gram hashes (boost 1) → fuzzy recall
        //
        // All tokens' hashes are merged into four sets, then a single
        // OpenSearch bool/should query is sent.  OpenSearch _score ordering
        // gives: starts-with first > ends-with > contains > fuzzy.

        Set<String> prefixSigSet    = new LinkedHashSet<>();
        Set<String> suffixSigSet    = new LinkedHashSet<>();
        Set<String> containsSigSet  = new LinkedHashSet<>();
        Set<String> neighbourSigSet = new LinkedHashSet<>();

        for (String token : tokens) {
            // Positional hashes of the query token itself
            prefixSigSet.addAll(phoneticSignatureService.indexPrefixSignatures(token));
            suffixSigSet.addAll(phoneticSignatureService.indexSuffixSignatures(token));
            containsSigSet.addAll(phoneticSignatureService.indexSignatures(token));

            // Neighbour hashes for fuzzy recall
            List<String> neighbours = phoneticSignatureService.neighbourTokens(token);
            neighbours.add(0, token);
            int limit = Math.min(NEIGHBOUR_COUNT+1, neighbours.size());
            // Clear search terms: debug only, never in the regular logs
            LOG.debug("Token '" + token + "' – top-" + limit + " neighbours: "
                    + neighbours.subList(0, limit));
            for (int i = 0; i < limit; i++) {
                neighbourSigSet.addAll(
                        phoneticSignatureService.indexSignatures(neighbours.get(i)));
            }
        }

        // Remove containsSigs from neighbourSigs so boosts don't overlap
        neighbourSigSet.removeAll(containsSigSet);

        LOG.info("Pass 2 sigs: prefix=" + prefixSigSet.size()
                + ", suffix=" + suffixSigSet.size()
                + ", contains=" + containsSigSet.size()
                + ", neighbour=" + neighbourSigSet.size());

        List<Long> personIds = personIndex.searchByPhoneticBoosted(
                tenant,
                List.copyOf(prefixSigSet),
                List.copyOf(suffixSigSet),
                List.copyOf(containsSigSet),
                List.copyOf(neighbourSigSet));

        LOG.info("Pass 2 (fuzzy) found " + personIds.size() + " person(s).");

        if (personIds.isEmpty()) {
            return List.of();
        }

        // Cap to MAX_RESULTS before loading from DB
        final List<Integer> orderedIds = (personIds.size() > MAX_RESULTS
                ? personIds.subList(0, MAX_RESULTS) : personIds)
                .stream().map(Long::intValue).toList();

        List<PersonEntity> entities = em
                .createNamedQuery(PersonEntity.SELECT_IN_IDS, PersonEntity.class)
                .setParameter("ids", orderedIds)
                .getResultList();

        // Re-apply OpenSearch score order (JPA IN-clause is unordered)
        entities.sort((a, b) ->
                Integer.compare(orderedIds.indexOf(a.id), orderedIds.indexOf(b.id)));

        List<PersonResponse> responses = toResponses(entities);

        // ── Post-processing sort: starts-with → ends-with → rest ────────────
        // Within each group the original OpenSearch _score order is preserved
        // because the stable sort only reorders across groups, not within.
        responses = new ArrayList<>(responses); // mutable copy for sort
        responses.sort((a, b) -> {
            int groupA = classifyMatch(a, tokens);
            int groupB = classifyMatch(b, tokens);
            return Integer.compare(groupA, groupB);
        });

        return responses;
    }

    /**
     * Classifies a person response into a match-quality group based on
     * whether any of its unprotected name labels start with, end with,
     * or merely overlap with one of the query tokens.
     *
     * <p>This operates on cleartext (already unprotected) data in the
     * {@link PersonResponse}, making it independent of the protected data
     * model stored in the database.
     *
     * @param person the unprotected person response
     * @param queryTokens lowercase query tokens (from {@link ProtectionService#tokenize})
     * @return 0 = starts with (best), 1 = ends with, 2 = rest
     */
    private static int classifyMatch(PersonResponse person, List<String> queryTokens) {
        String[] nameLabels = concatNameLabels(person);

        // Check "starts with" first (highest priority)
        for (String qt : queryTokens) {
            for (String label : nameLabels) {
                if (label.startsWith(qt)) return 0;
            }
        }

        // Check "ends with" next
        for (String qt : queryTokens) {
            for (String label : nameLabels) {
                if (label.endsWith(qt)) return 1;
            }
        }

        // Everything else
        return 2;
    }

    /**
     * Returns all individual lowercase name tokens from both the first and
     * last name of the given person response.
     */
    private static String[] concatNameLabels(PersonResponse person) {
        String first = person.firstName() != null ? person.firstName().toLowerCase(Locale.ROOT) : "";
        String last  = person.lastName()  != null ? person.lastName().toLowerCase(Locale.ROOT)  : "";
        return (first + " " + last).trim().split("\\s+");
    }

    // -----------------------------------------------------------------------
    // Pass 1 – exact match in the database
    // -----------------------------------------------------------------------

    /**
     * Looks up the persons whose {@code FIRST_NAME} or {@code LAST_NAME} is
     * exactly the query, or holds every word of it.
     *
     * <p>The stored values are protected as a whole and case-sensitively, so
     * each candidate value is protected in its case variants, see
     * {@link #caseVariants(String)}, with the mapping of each column.
     *
     * @param query the query as typed
     * @return the exactly matching persons; empty when none found
     */
    private List<PersonEntity> exactMatch(String query) {
        String whole = query.trim();
        List<String> words = Arrays.stream(whole.split("\\s+")).filter(w -> !w.isEmpty()).toList();

        List<List<String>> groups = new ArrayList<>();
        groups.add(caseVariants(whole));
        if (words.size() > 1) {
            words.forEach(word -> groups.add(caseVariants(word)));
        }
        List<Protected> protectedGroups = protectGroups(groups);

        // The whole query first: a stored "Jean-Claude" or "Le Gall"
        Set<Integer> ids = lookup(protectedGroups.get(0));
        if (ids.isEmpty() && words.size() > 1) {
            // Then every word, which a person must all match, in either field
            Set<Integer> intersection = null;
            for (Protected group : protectedGroups.subList(1, protectedGroups.size())) {
                Set<Integer> found = lookup(group);
                if (intersection == null) {
                    intersection = found;
                } else {
                    intersection.retainAll(found);
                }
                if (intersection.isEmpty()) {
                    return List.of();
                }
            }
            ids = intersection;
        }
        if (ids.isEmpty()) {
            return List.of();
        }

        List<Integer> ordered = new ArrayList<>(ids);
        ordered.sort(Integer::compare);
        return em.createNamedQuery(PersonEntity.SELECT_IN_IDS, PersonEntity.class)
                .setParameter("ids", ordered)
                .getResultList();
    }

    /**
     * The spellings a clear value may have been stored with: as typed,
     * Capitalized ({@code jean-claude} → {@code Jean-Claude}), lowercase and
     * UPPER.
     */
    static List<String> caseVariants(String value) {
        Set<String> variants = new LinkedHashSet<>();
        variants.add(value);
        variants.add(capitalize(value));
        variants.add(value.toLowerCase(Locale.ROOT));
        variants.add(value.toUpperCase(Locale.ROOT));
        return List.copyOf(variants);
    }

    /** Upper case the first letter of each part, the parts being separated by a space, a dash or an apostrophe. */
    static String capitalize(String value) {
        StringBuilder result = new StringBuilder(value.length());
        boolean start = true;
        for (char c : value.toLowerCase(Locale.ROOT).toCharArray()) {
            result.append(start ? Character.toUpperCase(c) : c);
            start = c == ' ' || c == '-' || c == '\'';
        }
        return result.toString();
    }

    /** The protected values of a group of candidates, for each name column. */
    private record Protected(List<String> firstNames, List<String> lastNames) {
    }

    /**
     * Protect every candidate with the mapping of {@code FIRST_NAME} and of
     * {@code LAST_NAME}, in a single transformer call.
     */
    private List<Protected> protectGroups(List<List<String>> groups) {
        List<ProtectionService.ColumnValue> values = new ArrayList<>();
        for (List<String> group : groups) {
            for (String candidate : group) {
                values.add(new ProtectionService.ColumnValue(PersonEntity.FIRST_NAME, candidate));
                values.add(new ProtectionService.ColumnValue(PersonEntity.LAST_NAME, candidate));
            }
        }
        List<String> protectedValues = protectionService.protectAll(values);

        List<Protected> result = new ArrayList<>(groups.size());
        int next = 0;
        for (List<String> group : groups) {
            List<String> firstNames = new ArrayList<>(group.size());
            List<String> lastNames = new ArrayList<>(group.size());
            for (int i = 0; i < group.size(); i++) {
                firstNames.add(protectedValues.get(next++));
                lastNames.add(protectedValues.get(next++));
            }
            result.add(new Protected(firstNames, lastNames));
        }
        return result;
    }

    private Set<Integer> lookup(Protected group) {
        List<Integer> ids = em.createNamedQuery(PersonEntity.SEARCH, Integer.class)
                .setParameter("firstNames", group.firstNames())
                .setParameter("lastNames", group.lastNames())
                .getResultList();
        return new HashSet<>(ids);
    }

    // -----------------------------------------------------------------------
    // Shared helpers
    // -----------------------------------------------------------------------

    /**
     * Unprotects every column of the given entities, in a single transformer
     * call, and builds the corresponding {@link PersonResponse} records.
     */
    private List<PersonResponse> toResponses(List<PersonEntity> entities) {
        List<ProtectionService.ColumnValue> values = new ArrayList<>(entities.size() * PersonEntity.PROTECTED_COLUMNS.size());
        for (PersonEntity p : entities) {
            values.add(new ProtectionService.ColumnValue(PersonEntity.FIRST_NAME, p.firstName));
            values.add(new ProtectionService.ColumnValue(PersonEntity.LAST_NAME, p.lastName));
            values.add(new ProtectionService.ColumnValue(PersonEntity.BIRTH_DATE, p.birthDate));
            values.add(new ProtectionService.ColumnValue(PersonEntity.EMAIL, p.email));
            values.add(new ProtectionService.ColumnValue(PersonEntity.CITY, p.city));
            values.add(new ProtectionService.ColumnValue(PersonEntity.NOTES, p.notes));
        }
        List<String> clear = protectionService.unprotectAll(values);

        List<PersonResponse> responses = new ArrayList<>(entities.size());
        for (int i = 0; i < entities.size(); i++) {
            int base = i * 6;
            responses.add(new PersonResponse(entities.get(i).id,
                    clear.get(base), clear.get(base + 1), tenantId,
                    clear.get(base + 2), clear.get(base + 3), clear.get(base + 4), clear.get(base + 5)));
        }
        return responses;
    }
}
