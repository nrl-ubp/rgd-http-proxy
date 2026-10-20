package com.ubp.rgd.proxy.transform.model;

import java.util.List;

/**
 * The evidences telling how to transform, starting with the {@code Action}: {@code Protect} or
 * {@code Unprotect}.
 */
public class ProcessingContext extends EvidenceContext {

    public ProcessingContext() {
    }

    public ProcessingContext(List<Evidence> evidences) {
        super(evidences);
    }
}
