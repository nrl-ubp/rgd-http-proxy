package com.ubp.rgd.proxy.fuzzysearch;

/**
 * Constants shared by the fuzzy search module.
 */
public final class FuzzySearch {

    /** Switches the fuzzy search on. When off, nothing of it is started and its endpoints answer 503. */
    public static final String ENABLED_PROPERTY = "fuzzy-search.enabled";

    /** Base path of every fuzzy search endpoint. */
    public static final String BASE_PATH = "/api/fuzzy-search";

    /** OpenAPI tag of the fuzzy search endpoints. */
    public static final String TAG = "Fuzzy search";

    private FuzzySearch() {
    }
}
