package com.ubp.rgd.proxy.fuzzysearch.service.search;

import com.ubp.rgd.proxy.fuzzysearch.FuzzySearch;
import com.ubp.rgd.proxy.fuzzysearch.jpa.FuzzySearchPersistence;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import com.ubp.rgd.proxy.fuzzysearch.entity.PersonEntity;
import com.ubp.rgd.proxy.fuzzysearch.service.phonetic.FastTextPhoneticSignatureService;
import com.ubp.rgd.proxy.fuzzysearch.service.protection.ProtectionService;
import com.ubp.rgd.proxy.fuzzysearch.service.protection.ProtectionService.ColumnValue;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.ToIntFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the search data from the protected {@code PERSON} table: the training
 * vocabulary of the fuzzy pass and the OpenSearch documents.
 *
 * <p>The table is read page by page. The names of a page are unprotected in a
 * single call to the transformer of the rgd-http-proxy, through
 * {@link ProtectionService}, so the data protected by the proxy (FPE or RPS) is
 * readable here. Nothing clear is written to disk: the vocabulary lives in the
 * memory of {@link FastTextPhoneticSignatureService}.
 *
 * <ul>
 *   <li>{@link #rebuildVocabulary(int)} rebuilds the vocabulary only. It also
 *       runs in the background at startup, see
 *       {@code fuzzy-search.training.rebuild-on-startup}.</li>
 *   <li>{@link #reindex(int)} is the clean reindex: the documents of the tenant
 *       are deleted, every row is indexed again and the vocabulary is rebuilt
 *       from the same pass, each name being unprotected once.</li>
 * </ul>
 * One run at a time: a second request while one is running is rejected with
 * {@link AlreadyRunningException}.
 */
@ApplicationScoped
public class TrainingDataService {

    private static final Logger LOG = LoggerFactory.getLogger(TrainingDataService.class);

    /** Loads a page of persons, ordered by identifier. */
    @FunctionalInterface
    public interface PageSource {
        List<PersonEntity> page(int offset, int size);
    }

    /** Thrown when a run is requested while another one is in progress. */
    public static class AlreadyRunningException extends IllegalStateException {
        public AlreadyRunningException() {
            super("A training data or reindex run is already in progress.");
        }
    }

    /**
     * Outcome of a vocabulary rebuild.
     *
     * @param rows           rows read from the table
     * @param vocabularySize distinct tokens of the new vocabulary
     * @param durationMs     elapsed time
     */
    public record TrainingResult(int rows, int vocabularySize, long durationMs) {
    }

    /**
     * Outcome of a clean reindex.
     *
     * @param deleted        documents of the tenant deleted before indexing
     * @param total          rows read from the table
     * @param indexed        rows indexed
     * @param errors         rows that could not be indexed
     * @param vocabularySize distinct tokens of the rebuilt vocabulary
     * @param durationMs     elapsed time
     */
    public record ReindexResult(long deleted, int total, int indexed, int errors, int vocabularySize,
                                long durationMs) {
    }

    @Inject
    @FuzzySearchPersistence
    EntityManagerFactory emf;

    @Inject
    ProtectionService protectionService;

    @Inject
    PersonIndexingService personIndexingService;

    @Inject
    OpenSearchPersonIndex personIndex;

    @Inject
    FastTextPhoneticSignatureService phoneticSignatureService;

    @ConfigProperty(name = "fuzzy-search.tenant-id", defaultValue = "default")
    String tenantId;

    @ConfigProperty(name = "fuzzy-search.training.batch-size", defaultValue = "500")
    int defaultBatchSize;

    @ConfigProperty(name = "fuzzy-search.training.rebuild-on-startup", defaultValue = "true")
    boolean rebuildOnStartup;

    @ConfigProperty(name = FuzzySearch.ENABLED_PROPERTY, defaultValue = "false")
    boolean enabled;

    private final ReentrantLock running = new ReentrantLock();

    /** Size of the last vocabulary built, -1 until the first one: read by the health check. */
    private volatile int vocabularySize = -1;

    /** Seams replaced by the tests; resolved lazily from the injected beans otherwise. */
    PageSource pageSource;
    ToIntFunction<Collection<String>> vocabularySink;

    /** Build a service outside of CDI, for the tests. */
    public static TrainingDataService of(PageSource pageSource, ProtectionService protectionService,
                                         PersonIndexingService personIndexingService,
                                         OpenSearchPersonIndex personIndex,
                                         ToIntFunction<Collection<String>> vocabularySink, String tenantId) {
        TrainingDataService service = new TrainingDataService();
        service.pageSource = pageSource;
        service.protectionService = protectionService;
        service.personIndexingService = personIndexingService;
        service.personIndex = personIndex;
        service.vocabularySink = vocabularySink;
        service.tenantId = tenantId;
        service.defaultBatchSize = 500;
        return service;
    }

    /** Rebuilds the vocabulary in the background once the application is up. */
    void onStart(@Observes StartupEvent event) {
        if (!enabled) {
            LOG.info("The fuzzy search is disabled (" + FuzzySearch.ENABLED_PROPERTY + "=false).");
            return;
        }
        if (!rebuildOnStartup) {
            LOG.info("fuzzy-search.training.rebuild-on-startup=false: no training vocabulary until"
                    + " POST /api/fuzzy-search/admin/training-data or .../reindex.");
            return;
        }
        Thread.ofVirtual().name("training-vocabulary").start(() -> {
            try {
                TrainingResult result = rebuildVocabulary(defaultBatchSize);
                LOG.info("Startup training vocabulary: " + result.vocabularySize() + " tokens from "
                        + result.rows() + " rows in " + result.durationMs() + " ms.");
            } catch (Exception e) {
                // The search keeps working, only without neighbour expansion.
                LOG.warn( "Could not build the training vocabulary at startup: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Rebuilds the training vocabulary from the names of the table.
     *
     * @param batchSize rows per page, the configured default when not positive
     * @return the rows read and the size of the new vocabulary
     * @throws AlreadyRunningException when another run is in progress
     */
    public TrainingResult rebuildVocabulary(int batchSize) {
        if (!running.tryLock()) {
            throw new AlreadyRunningException();
        }
        try {
            long start = System.currentTimeMillis();
            Set<String> tokens = new HashSet<>();
            int rows = forEachPage(batchSize, (page, clear) -> {
                for (int i = 0; i < page.size(); i++) {
                    tokens.addAll(protectionService.tokenize(clear.get(2 * i)));
                    tokens.addAll(protectionService.tokenize(clear.get(2 * i + 1)));
                }
            });
            int size = vocabularySink().applyAsInt(tokens);
            vocabularySize = size;
            return new TrainingResult(rows, size, System.currentTimeMillis() - start);
        } finally {
            running.unlock();
        }
    }

    /**
     * Clean reindex: deletes the documents of the tenant, indexes every row of
     * the table and rebuilds the vocabulary from the same pass.
     *
     * <p>A page that fails as a whole is retried row by row, the rows failing
     * again being counted as errors. A failure to delete the documents aborts
     * the reindex before anything is indexed.
     *
     * @param batchSize rows per page, the configured default when not positive
     * @return the counts of the run
     * @throws AlreadyRunningException when another run is in progress
     */
    public ReindexResult reindex(int batchSize) {
        if (!running.tryLock()) {
            throw new AlreadyRunningException();
        }
        try {
            long start = System.currentTimeMillis();
            long deleted = personIndex.deleteTenant(tenantId);

            Set<String> tokens = new HashSet<>();
            int[] counts = new int[2]; // indexed, errors
            int total = forEachPage(batchSize, (page, clear) -> {
                for (int i = 0; i < page.size(); i++) {
                    PersonEntity entity = page.get(i);
                    try {
                        tokens.addAll(personIndexingService.indexClear(entity, clear.get(2 * i), clear.get(2 * i + 1)));
                        counts[0]++;
                    } catch (Exception e) {
                        counts[1]++;
                        LOG.warn( "Failed to reindex person id=" + entity.id + ": " + e.getMessage(), e);
                    }
                }
            }, entity -> counts[1]++);
            personIndex.refresh();

            int size = vocabularySink().applyAsInt(tokens);
            vocabularySize = size;
            LOG.info("Reindex completed: deleted=" + deleted + ", total=" + total + ", indexed=" + counts[0]
                    + ", errors=" + counts[1] + ", vocabulary=" + size);
            return new ReindexResult(deleted, total, counts[0], counts[1], size, System.currentTimeMillis() - start);
        } finally {
            running.unlock();
        }
    }

    /**
     * @return the size of the last vocabulary built, -1 when none was built yet. Never loads the
     *         FastText model, unlike asking the phonetic service.
     */
    public int lastVocabularySize() {
        return vocabularySize;
    }

    /** @return whether a vocabulary rebuild or a reindex is running */
    public boolean isRunning() {
        return running.isLocked();
    }

    // -----------------------------------------------------------------------
    // Paging
    // -----------------------------------------------------------------------

    /** Receives a page and the clear names: {@code clear[2i]} first name, {@code clear[2i+1]} last name. */
    @FunctionalInterface
    private interface PageConsumer {
        void accept(List<PersonEntity> page, List<String> clear);
    }

    private int forEachPage(int batchSize, PageConsumer consumer) {
        return forEachPage(batchSize, consumer, entity -> {
        });
    }

    /**
     * Reads the table page by page and unprotects the names of each page in a
     * single call. When a page cannot be unprotected as a whole, its rows are
     * unprotected one by one, a row failing again being handed to
     * {@code onRowError} and skipped.
     *
     * @return the number of rows read
     */
    private int forEachPage(int batchSize, PageConsumer consumer, java.util.function.Consumer<PersonEntity> onRowError) {
        int size = batchSize > 0 && batchSize <= 10_000 ? batchSize : defaultBatchSize;
        PageSource source = pageSource();
        int offset = 0;
        int rows = 0;
        while (true) {
            List<PersonEntity> page = source.page(offset, size);
            if (page.isEmpty()) {
                break;
            }
            rows += page.size();
            List<String> clear = null;
            try {
                clear = unprotectNames(page);
            } catch (RuntimeException pageError) {
                LOG.warn( "Failed to unprotect a page of " + page.size()
                        + " person(s), retrying them one by one: " + pageError.getMessage());
            }
            if (clear != null) {
                consumer.accept(page, clear);
            } else {
                for (PersonEntity entity : page) {
                    List<String> rowClear;
                    try {
                        rowClear = unprotectNames(List.of(entity));
                    } catch (RuntimeException rowError) {
                        LOG.warn( "Failed to unprotect person id=" + entity.id + ": "
                                + rowError.getMessage(), rowError);
                        onRowError.accept(entity);
                        continue;
                    }
                    consumer.accept(List.of(entity), rowClear);
                }
            }
            LOG.info("Processed " + rows + " row(s)");
            if (page.size() < size) {
                break;
            }
            offset += size;
        }
        return rows;
    }

    private List<String> unprotectNames(List<PersonEntity> page) {
        List<ColumnValue> names = new ArrayList<>(page.size() * 2);
        for (PersonEntity entity : page) {
            names.add(new ColumnValue(PersonEntity.FIRST_NAME, entity.firstName));
            names.add(new ColumnValue(PersonEntity.LAST_NAME, entity.lastName));
        }
        return protectionService.unprotectAll(names);
    }

    private PageSource pageSource() {
        if (pageSource == null) {
            // Own EntityManager per page: this also runs outside any request scope, at startup.
            pageSource = (offset, size) -> {
                EntityManager em = emf.createEntityManager();
                try {
                    return em.createNamedQuery(PersonEntity.SELECT_ALL, PersonEntity.class)
                            .setFirstResult(offset)
                            .setMaxResults(size)
                            .getResultList();
                } finally {
                    em.close();
                }
            };
        }
        return pageSource;
    }

    private ToIntFunction<Collection<String>> vocabularySink() {
        if (vocabularySink == null) {
            vocabularySink = phoneticSignatureService::rebuildVocabulary;
        }
        return vocabularySink;
    }
}
