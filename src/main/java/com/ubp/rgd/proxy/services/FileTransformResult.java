package com.ubp.rgd.proxy.services;

import java.util.List;

/**
 * Outcome of one run of a file transformation configuration.
 * <p>
 * A run is reported by its two counts rather than by a single "processed" number, because a file
 * that failed is also a file that was processed: conflating them is what made a completely failed
 * batch look like a success to a caller.
 *
 * @param configuration  the name of the configuration that was run
 * @param filesSucceeded number of files transformed and moved to the target directory
 * @param filesFailed    number of files that failed and were moved to the error directory
 * @param errors         one message per failure, naming the file and the cause
 */
public record FileTransformResult(String configuration, int filesSucceeded, int filesFailed,
                                  List<String> errors) {

    /**
     * @return the number of files this run dealt with, successfully or not
     */
    public int filesProcessed() {
        return filesSucceeded + filesFailed;
    }

    /**
     * A run has failed as soon as a file could not be transformed, or as soon as the source
     * directory itself could not be walked. The latter produces no failed file, so the error list
     * has to be consulted too.
     *
     * @return true when the run did not complete cleanly
     */
    public boolean hasFailures() {
        return filesFailed > 0 || !errors.isEmpty();
    }

    /**
     * @return true when the configuration matched no file at all and nothing went wrong
     */
    public boolean isEmpty() {
        return filesProcessed() == 0 && errors.isEmpty();
    }

    /**
     * @return {@code success}, {@code partial}, {@code error} or {@code empty}
     */
    public String status() {
        if (isEmpty()) {
            return "empty";
        }
        if (!hasFailures()) {
            return "success";
        }
        return filesSucceeded > 0 ? "partial" : "error";
    }
}
