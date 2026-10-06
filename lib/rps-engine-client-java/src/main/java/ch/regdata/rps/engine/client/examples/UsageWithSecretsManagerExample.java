package ch.regdata.rps.engine.client.examples;

import ch.regdata.rps.engine.client.*;
import ch.regdata.rps.engine.client.enginecontext.ProcessingContext;
import ch.regdata.rps.engine.client.enginecontext.RPSEngineContextResolver;
import ch.regdata.rps.engine.client.http.HttpClientEngineProvider;
import ch.regdata.rps.engine.client.mapping.RPSMapping;
import ch.regdata.rps.engine.client.model.api.value.IRPSValue;
import ch.regdata.rps.engine.client.model.api.value.RPSValue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.UUID;

public class UsageWithSecretsManagerExample {

    public static void main(String[] args) {

        // Get the engine provider with connection details
        HttpClientEngineProvider engineProvider = EngineProviderFactory.getEngineProvider();

        System.out.println("--- Example of protection using different secrets managers in a single call ---");
        Context adminRightsContext = new Context(new ArrayList<>(Arrays.asList(
                new Evidence("Role", "Admin")
        )));
        ProcessingContext protectProcessingContext = new ProcessingContext(new ArrayList<>(Arrays.asList(
                new Evidence("Action", "Protect")
        )));

        // Identifiers of the secrets managers configured in RPS CoreConfiguration (replace with your own)
        UUID firstSecretsManager = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID secondSecretsManager = UUID.fromString("00000000-0000-0000-0000-000000000002");

        RPSValue firstName = new RPSValue(new RPSMapping("User", "FirstName"), "Jonny");
        RPSValue lastName = new RPSValue(new RPSMapping("User", "LastName"), "Silverhand");

        try {
            RPSEngine engine = new RPSEngine(engineProvider,
                    new RPSEngineConverter(),
                    new RPSEngineContextResolver(null));

            // Each request of the context can use its own secrets manager
            RequestContext requestContext = engine
                    .createContext()
                    .withRequest(
                            new IRPSValue[]{firstName},
                            adminRightsContext,
                            protectProcessingContext,
                            null,
                            firstSecretsManager)
                    .withRequest(
                            new IRPSValue[]{lastName},
                            adminRightsContext,
                            protectProcessingContext,
                            null,
                            secondSecretsManager);

            requestContext.transform();
            System.out.println("First name. Original: " + firstName.getOriginal() + ". Transformed: " + firstName.getTransformed());
            System.out.println("Last name. Original: " + lastName.getOriginal() + ". Transformed: " + lastName.getTransformed());
            System.out.println();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
