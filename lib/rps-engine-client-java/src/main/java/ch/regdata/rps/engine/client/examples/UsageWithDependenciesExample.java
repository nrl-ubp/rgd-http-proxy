package ch.regdata.rps.engine.client.examples;

import ch.regdata.rps.engine.client.RPSEngine;
import ch.regdata.rps.engine.client.RPSEngineConverter;
import ch.regdata.rps.engine.client.enginecontext.RPSEngineContextJsonFileProvider;
import ch.regdata.rps.engine.client.enginecontext.RPSEngineContextResolver;
import ch.regdata.rps.engine.client.http.HttpClientEngineProvider;
import ch.regdata.rps.engine.client.mapping.RPSMapping;
import ch.regdata.rps.engine.client.model.api.value.IRPSValue;
import ch.regdata.rps.engine.client.model.api.value.RPSValue;

import java.nio.file.Paths;

public class UsageWithDependenciesExample {


    public static void main(String[] args) {

        // Get the engine provider with connection details
        HttpClientEngineProvider engineProvider = EngineProviderFactory.getEngineProvider();
        try {
            RPSEngineContextJsonFileProvider engineContextJsonFileProvider = new RPSEngineContextJsonFileProvider(
                    (Paths.get(UsageWithDependenciesExample.class.getClassLoader().getResource("RightsContexts.json").toURI())).toString(),
                    (Paths.get(UsageWithDependenciesExample.class.getClassLoader().getResource("ProcessingContexts.json").toURI())).toString()
            );
            engineContextJsonFileProvider.initialize();

            System.out.println("--- Example of protection with dependencies ---");

            // Different constructors with ability to specify Instance, Original value and Dependencies.
            RPSValue paymentDate = new RPSValue(new RPSMapping("Payment", "Date"), "02.11.2021");
            paymentDate.addDependency("min", "01.10.2022");
            paymentDate.addDependency("max", "02.11.2022");
            RPSValue paymentAmount = new RPSValue(new RPSMapping("Payment", "Amount"), "999");


            RPSEngine engine = new RPSEngine(engineProvider,
                    new RPSEngineConverter(),
                    new RPSEngineContextResolver(engineContextJsonFileProvider));

            // This method will do the call to RPS Engine API.
            engine.transform(new IRPSValue[]{paymentDate, paymentAmount}, "Admin", "Protect");

            // Get the protected values
            System.out.println("Payment date. Original: " + paymentDate.getOriginal() + ". Transformed: " + paymentDate.getTransformed());
            System.out.println("Payment amount. Original: " + paymentAmount.getOriginal() + ". Transformed: " + paymentAmount.getTransformed());
            System.out.println();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
