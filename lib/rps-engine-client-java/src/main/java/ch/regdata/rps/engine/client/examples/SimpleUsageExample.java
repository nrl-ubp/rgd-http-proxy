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

public class SimpleUsageExample {

    public static void main(String[] args) {

        // Get the engine provider with connection details
        HttpClientEngineProvider engineProvider = EngineProviderFactory.getEngineProvider();

        System.out.println("--- Example of simple protection and deprotection ---");
        // Manually creates admin rights context.
        Context adminRightsContext = new Context(new ArrayList<>(Arrays.asList(
                new Evidence("Role", "Admin")
        )));
        // Manually creates protecting processing context.
        ProcessingContext protectProcessingContext = new ProcessingContext(new ArrayList<>(Arrays.asList(
                new Evidence("Action", "Protect")
        )));
        // Manually creates deprotecting processing context.
        ProcessingContext deprotectProcessingContext = new ProcessingContext(new ArrayList<>(Arrays.asList(
                new Evidence("Action", "Deprotect")
        )));
        // Different constructors with ability to specify Instance and Original value.
        RPSValue rawFirstName = new RPSValue(new RPSMapping("User", "FirstName"), "Jonny");
        RPSValue rawLastName = new RPSValue(new RPSMapping("User", "LastName"), "Silverhand");
        RPSValue rawBirthDate = new RPSValue(new RPSMapping("User", "BirthDate"), "16.11.1988");

        RPSValue protectedFirstName = new RPSValue(new RPSMapping("User", "FirstName"), "n99toNMwdjjGtWs3SxkrxQ==");
        RPSValue protectedLastName = new RPSValue(new RPSMapping("User", "LastName"), "FLGqfDklPngzYAD8066q40drM1jZYQzKktF1YO81A==");
        RPSValue protectedBirthDate = new RPSValue(new RPSMapping("User", "BirthDate"), "28.03.1985");


        try {
            RPSEngine engine = new RPSEngine(engineProvider,
                    new RPSEngineConverter(),
                    new RPSEngineContextResolver(null));

            RequestContext requestContext = engine
                    .createContext()
                    .withRequest(
                            new IRPSValue[]{rawFirstName, rawLastName, rawBirthDate},
                            adminRightsContext,
                            protectProcessingContext)
                    .withRequest(
                            new IRPSValue[]{protectedFirstName, protectedLastName, protectedBirthDate},
                            adminRightsContext,
                            deprotectProcessingContext);

            // Calls the transformation API -> will lead to protect the data
            requestContext.transform();
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
