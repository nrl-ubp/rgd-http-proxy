package com.ubp.rgd.proxy.fuzzysearch.service.search;

import com.ubp.rgd.proxy.fpe.FPEEndPointTransformer;
import com.ubp.rgd.proxy.fuzzysearch.entity.PersonEntity;
import com.ubp.rgd.proxy.fuzzysearch.service.phonetic.PhoneticSignatureService;
import com.ubp.rgd.proxy.fuzzysearch.service.protection.ColumnMappingConfig;
import com.ubp.rgd.proxy.fuzzysearch.service.protection.ProtectionService;
import com.ubp.rgd.proxy.fuzzysearch.service.protection.TransformerProtectionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link TrainingDataService} over a protected in-memory table: the names are really protected
 * with the FPE transformer of the proxy, and must come back in clear in the index and the vocabulary.
 */
class TrainingDataServiceTest {

    private static final String KEY = "2b7e151628aed2a6abf7158809cf4f3c";
    private static final String TENANT = "default";

    private final ProtectionService protection = TransformerProtectionService.of(
            FPEEndPointTransformer.withKey(KEY),
            ColumnMappingConfig.load("src/test/resources/flight_sql_mapping_config.json"));

    private final RecordingIndex index = new RecordingIndex();
    private final AtomicReference<Set<String>> vocabulary = new AtomicReference<>();

    /** Five persons, protected like the proxy's loader protects them. */
    private final List<PersonEntity> table = List.of(
            person(1, "Jean-Claude", "Dusse"),
            person(2, "Bernadette", "Lafont"),
            person(3, "Jean Paul", "Belmondo"),
            person(4, "Gigi", null),
            person(5, "Popeye", "Dusse"));

    private PersonEntity person(int id, String first, String last) {
        PersonEntity p = new PersonEntity();
        p.id = id;
        p.firstName = protection.protect(PersonEntity.FIRST_NAME, first);
        p.lastName = protection.protect(PersonEntity.LAST_NAME, last);
        return p;
    }

    private TrainingDataService service(TrainingDataService.PageSource source) {
        PersonIndexingService indexing = new PersonIndexingService();
        indexing.protectionService = protection;
        indexing.phoneticSignatureService = new ClearSignatures();
        indexing.personIndex = index;
        indexing.tenantId = TENANT;
        return TrainingDataService.of(source, protection, indexing, index, tokens -> {
            vocabulary.set(new TreeSet<>(tokens));
            return tokens.size();
        }, TENANT);
    }

    private static TrainingDataService.PageSource pagesOf(List<PersonEntity> rows) {
        return (offset, size) -> rows.subList(Math.min(offset, rows.size()), Math.min(offset + size, rows.size()));
    }

    @Test
    @DisplayName("The vocabulary holds the distinct lowercase clear name tokens of every page")
    void vocabularyIsBuiltFromTheClearNames() {
        TrainingDataService.TrainingResult result = service(pagesOf(table)).rebuildVocabulary(2);

        assertEquals(5, result.rows());
        assertEquals(Set.of("jean-claude", "dusse", "bernadette", "lafont", "jean", "paul", "belmondo",
                "gigi", "popeye"), vocabulary.get());
        assertEquals(9, result.vocabularySize());
        assertTrue(index.documents.isEmpty(), "the vocabulary rebuild must not touch the index");
    }

    @Test
    @DisplayName("The reindex deletes the tenant first, then indexes every row from its clear names")
    void reindexIsClean() {
        TrainingDataService.ReindexResult result = service(pagesOf(table)).reindex(2);

        assertEquals("delete:" + TENANT, index.events.get(0));
        assertEquals("refresh", index.events.get(index.events.size() - 1));
        assertEquals(7, result.deleted());
        assertEquals(5, result.total());
        assertEquals(5, result.indexed());
        assertEquals(0, result.errors());
        assertEquals(9, result.vocabularySize());

        // The signatures are those of the clear names, so the protected values were unprotected.
        PersonSearchDocument jean = index.documents.stream()
                .filter(d -> d.docId.equals("person-3")).findFirst().orElseThrow();
        assertEquals(List.of("jean", "paul", "belmondo"), jean.phonetic);
        assertEquals(TENANT, jean.tenantId);
    }

    @Test
    @DisplayName("A page that cannot be unprotected is retried row by row, the failing rows counted")
    void failingPageFallsBackToRows() {
        List<PersonEntity> rows = new ArrayList<>(table);
        PersonEntity broken = new PersonEntity();
        broken.id = 6;
        broken.firstName = "RG{Z9broken}";
        broken.lastName = "x";
        rows.add(broken);

        TrainingDataService service = service(pagesOf(rows));
        service.protectionService = new ProtectionService() {
            @Override
            public List<String> tokenize(String clear) {
                return protection.tokenize(clear);
            }

            @Override
            public List<String> protectAll(List<ColumnValue> values) {
                return protection.protectAll(values);
            }

            @Override
            public List<String> unprotectAll(List<ColumnValue> values) {
                if (values.stream().anyMatch(v -> "RG{Z9broken}".equals(v.value()))) {
                    throw new IllegalStateException("engine rejected a token");
                }
                return protection.unprotectAll(values);
            }
        };

        TrainingDataService.ReindexResult result = service.reindex(4);

        assertEquals(6, result.total());
        assertEquals(5, result.indexed());
        assertEquals(1, result.errors());
        assertEquals(5, index.documents.size(), "no row may be indexed twice");
    }

    @Test
    @DisplayName("A failing deletion aborts the reindex before anything is indexed")
    void failingDeleteAbortsTheReindex() {
        index.failDelete = true;

        assertThrows(IllegalStateException.class, () -> service(pagesOf(table)).reindex(2));
        assertTrue(index.documents.isEmpty());
    }

    @Test
    @DisplayName("A second run while one is in progress is rejected")
    void oneRunAtATime() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TrainingDataService service = service((offset, size) -> {
            inside.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return List.of();
        });

        Thread first = Thread.ofVirtual().start(() -> service.rebuildVocabulary(10));
        inside.await();
        try {
            assertThrows(TrainingDataService.AlreadyRunningException.class, () -> service.reindex(10));
        } finally {
            release.countDown();
            first.join();
        }
        // Released: a new run is accepted again.
        assertEquals(0, service.rebuildVocabulary(10).rows());
    }

    /** One "signature" per clear token, so the test can read what was indexed. */
    private static class ClearSignatures implements PhoneticSignatureService {
        public String signature(String token) { return token; }
        public List<String> indexSignatures(String token) { return List.of(token); }
        public List<String> indexPrefixSignatures(String token) { return List.of(token); }
        public List<String> indexSuffixSignatures(String token) { return List.of(token); }
        public List<String> neighbourSignatures(String token) { return List.of(); }
        public List<String> neighbourTokens(String token) { return new ArrayList<>(); }
    }

    private static class RecordingIndex implements OpenSearchPersonIndex {
        final List<String> events = new ArrayList<>();
        final List<PersonSearchDocument> documents = new ArrayList<>();
        boolean failDelete;

        public void index(PersonSearchDocument doc) {
            events.add("index:" + doc.docId);
            documents.add(doc);
        }

        public long deleteTenant(String tenantId) {
            if (failDelete) {
                throw new IllegalStateException("OpenSearch is down");
            }
            events.add("delete:" + tenantId);
            return 7;
        }

        public void refresh() {
            events.add("refresh");
        }

        public List<Long> searchByPhonetic(String tenantId, List<String> phonetic) { return List.of(); }

        public List<Long> searchByPhoneticBoosted(String tenantId, List<String> p, List<String> s,
                                                  List<String> c, List<String> n) { return List.of(); }
    }
}
