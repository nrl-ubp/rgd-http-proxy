package com.ubp.rgd.proxy.transform.model;

import java.util.List;

/**
 * The evidences telling who asks for a transformation, used by the tokenizer to check the rights.
 */
public class RightContext extends EvidenceContext {

    public RightContext() {
    }

    public RightContext(List<Evidence> evidences) {
        super(evidences);
    }
}
