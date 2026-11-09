package com.ubp.rgd.proxy.fuzzysearch.service.search;

import jakarta.inject.Inject;
import jakarta.enterprise.context.ApplicationScoped;
import com.ubp.rgd.proxy.fuzzysearch.entity.PersonEntity;
import com.ubp.rgd.proxy.fuzzysearch.service.phonetic.PhoneticSignatureService;
import com.ubp.rgd.proxy.fuzzysearch.service.protection.ProtectionService;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * Single shared service responsible for building the OpenSearch phonetic
 * document for a {@link PersonEntity} and pushing it to the index.
 *
 * <h2>Why a dedicated service?</h2>
 * Both {@code PersonResource.create()} (real-time indexing of a newly
 * persisted person) and {@code AdminResource.reindex()} (bulk rebuild of the
 * whole table) must produce <em>identical</em> {@link PersonSearchDocument}
 * objects for the same entity.  Having the logic in a single bean guarantees
 * that one code path is used everywhere.
 *
 * <h2>Pipeline</h2>
 * <ol>
 *   <li>Unprotect {@link PersonEntity#firstName} / {@link PersonEntity#lastName}
 *       with the transformer of the rgd-http-proxy, see
 *       {@link ProtectionService#unprotectAll(List)}. A whole page of persons is
 *       unprotected in a single transformer call.</li>
 *   <li>Split the clear names on whitespace and lowercase them.</li>
 *   <li>Compute phonetic signatures (all, prefix, suffix) via
 *       {@link PhoneticSignatureService}.</li>
 *   <li>Build a {@link PersonSearchDocument}, scoped to the configured
 *       {@code fuzzy-search.tenant-id}, and push it to OpenSearch via
 *       {@link OpenSearchPersonIndex#index(PersonSearchDocument)}.</li>
 * </ol>
 */
@ApplicationScoped
public class PersonIndexingService {

    @Inject
    ProtectionService protectionService;

    @Inject
    PhoneticSignatureService phoneticSignatureService;

    @Inject
    OpenSearchPersonIndex personIndex;

    /** The PERSON table has no tenant: every document is indexed under this one. */
    @ConfigProperty(name = "fuzzy-search.tenant-id", defaultValue = "default")
    String tenantId;

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Builds and upserts the OpenSearch document for {@code entity}.
     *
     * @param entity a persisted {@link PersonEntity} with a valid {@code id}
     */
    public void indexPerson(PersonEntity entity) {
        indexPersons(List.of(entity));
    }

    /**
     * Builds and upserts the OpenSearch documents of several persons, their
     * names being unprotected in a single transformer call.
     *
     * <p>This is the <strong>single authoritative indexing path</strong> used
     * by both real-time ({@code PersonResource.create}) and bulk
     * ({@code AdminResource.reindex}) operations.
     *
     * <p>Three sets of n-gram hashes are computed per token:
     * <ul>
     *   <li>{@code phonetic} – ALL 3-gram + 4-gram hashes (for "contains" matching).</li>
     *   <li>{@code phonetic_prefix} – hashes of n-grams starting at position 0
     *       (for "starts-with" boosting at search time).</li>
     *   <li>{@code phonetic_suffix} – hashes of n-grams ending at the last
     *       character (for "ends-with" boosting at search time).</li>
     * </ul>
     *
     * @param entities persisted persons with a valid {@code id}
     */
    public void indexPersons(List<PersonEntity> entities) {
        List<ProtectionService.ColumnValue> names = new ArrayList<>(entities.size() * 2);
        for (PersonEntity entity : entities) {
            names.add(new ProtectionService.ColumnValue(PersonEntity.FIRST_NAME, entity.firstName));
            names.add(new ProtectionService.ColumnValue(PersonEntity.LAST_NAME, entity.lastName));
        }
        List<String> clear = protectionService.unprotectAll(names);

        for (int i = 0; i < entities.size(); i++) {
            indexClear(entities.get(i), clear.get(2 * i), clear.get(2 * i + 1));
        }
    }

    /**
     * Builds and upserts the OpenSearch document of a person whose names are
     * already unprotected, for the callers that unprotect a whole page at once.
     *
     * @param entity    a persisted person with a valid {@code id}
     * @param firstName its clear first name, may be {@code null}
     * @param lastName  its clear last name, may be {@code null}
     * @return the lowercase name tokens the document was built from
     */
    public List<String> indexClear(PersonEntity entity, String firstName, String lastName) {
        List<String> tokenList = new ArrayList<>(protectionService.tokenize(firstName));
        tokenList.addAll(protectionService.tokenize(lastName));
        personIndex.index(document(entity, tokenList));
        return tokenList;
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    private PersonSearchDocument document(PersonEntity entity, List<String> tokenList) {
        // ALL n-gram hashes
        List<String> phonetic = tokenList.stream()
                .flatMap(t -> phoneticSignatureService.indexSignatures(t).stream())
                .distinct()
                .toList();

        // Prefix n-gram hashes (position 0)
        List<String> phoneticPrefix = tokenList.stream()
                .flatMap(t -> phoneticSignatureService.indexPrefixSignatures(t).stream())
                .distinct()
                .toList();

        // Suffix n-gram hashes (ending at last char)
        List<String> phoneticSuffix = tokenList.stream()
                .flatMap(t -> phoneticSignatureService.indexSuffixSignatures(t).stream())
                .distinct()
                .toList();

        PersonSearchDocument doc = new PersonSearchDocument();
        doc.docId          = "person-" + entity.id;
        doc.tenantId       = tenantId;
        doc.phonetic       = phonetic;
        doc.phoneticPrefix = phoneticPrefix;
        doc.phoneticSuffix = phoneticSuffix;
        return doc;
    }
}
