package ch.regdata.rps.engine.client.examples;

import ch.regdata.rps.engine.client.RPSEngine;
import ch.regdata.rps.engine.client.RPSEngineConverter;
import ch.regdata.rps.engine.client.RequestContext;
import ch.regdata.rps.engine.client.enginecontext.RPSEngineContextJsonFileProvider;
import ch.regdata.rps.engine.client.enginecontext.RPSEngineContextResolver;
import ch.regdata.rps.engine.client.http.HttpClientEngineProvider;
import ch.regdata.rps.engine.client.mapping.RPSMapping;
import ch.regdata.rps.engine.client.model.api.value.IRPSValue;
import ch.regdata.rps.engine.client.model.api.value.RPSValue;

import java.nio.file.Paths;

public class ContextsProvidedByResolverExample {

    public static void main(String[] args) {

        // Get the engine provider with connection details
        HttpClientEngineProvider engineProvider = EngineProviderFactory.getEngineProvider();
        try {

            RPSEngineContextJsonFileProvider engineContextJsonFileProvider = new RPSEngineContextJsonFileProvider(
                    (Paths.get(ContextsProvidedByResolverExample.class.getClassLoader().getResource("RightsContexts.json").toURI())).toString(),
                    (Paths.get(ContextsProvidedByResolverExample.class.getClassLoader().getResource("ProcessingContexts.json").toURI())).toString()
            );

            engineContextJsonFileProvider.initialize();

            System.out.println("--- Example with rights and processing contexts provided by abstract resolver ---");

            // Different constructors with ability to specify Instance and Original value.
            RPSValue rawFirstName = new RPSValue(new RPSMapping("User", "FirstName"), "Jonny");
            RPSValue rawLastName = new RPSValue(new RPSMapping("User", "LastName"), "Silverhand");
            RPSValue rawBirthDate = new RPSValue(new RPSMapping("User", "BirthDate"), "16.11.1988");

            RPSValue protectedFirstName = new RPSValue(new RPSMapping("User", "FirstName"), "n99toNMwdjjGtWs3SxkrxQ==");
            RPSValue protectedLastName = new RPSValue(new RPSMapping("User", "LastName"), "FLGqfDklPngzYAD8066q40drM1jZYQzKktF1YO81A==");
            RPSValue protectedBirthDate = new RPSValue(new RPSMapping("User", "BirthDate"), "28.03.1985");

            RPSEngine engine = new RPSEngine(engineProvider,
                    new RPSEngineConverter(),
                    new RPSEngineContextResolver(engineContextJsonFileProvider));

            // In this example we create a two different contexts (protect and deprotect)
            // with a single request inside each, it will do a two call to RPS Engine API.
            RequestContext protectRequestContext = engine
                    .createContext()
                    .withRequest(
                            new IRPSValue[]{rawFirstName, rawLastName, rawBirthDate},
                            "Admin",
                            "Protect");

            RequestContext deprotectRequestContext = engine
                    .createContext()
                    .withRequest(
                            new IRPSValue[]{protectedFirstName, protectedLastName, protectedBirthDate},
                            "Admin",
                            "Deprotect");

            // This methods will do REST API calls to RPS Engine API.
            protectRequestContext.transform();
            deprotectRequestContext.transform();
            // Get the protected values
            System.out.println("Raw fist name. Original: " + rawFirstName.getOriginal() + ". Transformed: " + rawFirstName.getTransformed());
            System.out.println("Raw last name. Original: " + rawLastName.getOriginal() + ". Transformed: " + rawLastName.getTransformed());
            System.out.println("Raw birth date. Original: " + rawBirthDate.getOriginal() + ". Transformed: " + rawBirthDate.getTransformed());
            System.out.println("Protected first name. Original: " + protectedFirstName.getOriginal() + ". Transformed: " + protectedFirstName.getTransformed());
            System.out.println("Protected last name. Original: " + protectedLastName.getOriginal() + ". Transformed: " + protectedLastName.getTransformed());
            System.out.println("Protected birth date. Original: " + protectedBirthDate.getOriginal() + ". Transformed: " + protectedBirthDate.getTransformed());
            System.out.println();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
