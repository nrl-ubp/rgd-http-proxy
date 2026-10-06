package ch.regdata.rps.engine.client.extensions;

import ch.regdata.rps.engine.client.model.api.context.Context;
import ch.regdata.rps.engine.client.model.api.context.Evidence;

import java.util.*;

public class ContextExtensions {

    public static Context ToContext(HashMap<String, String> dictionary, UUID contextGuid) {
        Context context = new Context();
        List<Evidence> evidences = new ArrayList<Evidence>();
        context.setGuid(contextGuid);
        context.setEvidences(evidences);

        if (dictionary != null) {
            Set<Map.Entry<String, String>> entries = dictionary.entrySet();
            for (Map.Entry<String, String> entry : entries) {
                Evidence evidence = new Evidence();
                evidence.setName(entry.getKey());
                evidence.setValue(entry.getValue());
                evidences.add(evidence);
            }
        }
        return context;
    }
}
