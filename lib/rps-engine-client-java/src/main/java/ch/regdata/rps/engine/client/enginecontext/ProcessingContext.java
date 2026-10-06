package ch.regdata.rps.engine.client.enginecontext;

import ch.regdata.rps.engine.client.Context;
import ch.regdata.rps.engine.client.Evidence;

import java.util.ArrayList;

public class ProcessingContext extends Context {
    public ProcessingContext() {
        super();
    }

    public ProcessingContext(ArrayList<Evidence> evidences) {
        super(evidences);
    }
}
