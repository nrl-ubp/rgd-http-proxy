package com.ubp.rgd.proxy.fuzzysearch.dto;

/**
 * Summary returned by {@code POST /admin/reindex}.
 *
 * @param deleted        Documents of the tenant deleted from OpenSearch before indexing.
 * @param total          Total number of rows found in the PERSON table.
 * @param indexed        Number of persons successfully indexed in OpenSearch.
 * @param errors         Number of persons that failed due to an exception.
 * @param vocabularySize Distinct name tokens of the rebuilt training vocabulary.
 * @param durationMs     Wall-clock time of the whole reindex operation in milliseconds.
 */
public record ReindexResponse(
        long deleted,
        int total,
        int indexed,
        int errors,
        int vocabularySize,
        long durationMs
) {}
