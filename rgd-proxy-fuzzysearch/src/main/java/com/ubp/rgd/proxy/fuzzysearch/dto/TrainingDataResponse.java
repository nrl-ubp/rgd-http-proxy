package com.ubp.rgd.proxy.fuzzysearch.dto;

/**
 * Summary returned by {@code POST /admin/training-data}.
 *
 * @param rows           Rows read from the PERSON table.
 * @param vocabularySize Distinct name tokens of the rebuilt training vocabulary.
 * @param durationMs     Wall-clock time of the rebuild in milliseconds.
 */
public record TrainingDataResponse(
        int rows,
        int vocabularySize,
        long durationMs
) {}
