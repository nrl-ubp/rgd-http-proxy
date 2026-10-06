# rps-engine-client-java

Regdata RPS Engine Client library (Java)

## RPS Engine Client library documentation

The full documentation is available in [RPS Community](https://community.rpsprod.ch/library)

## Installation

Use `maven` to install the RPS Engine Client library. Add the following dependency in dependencies section of your pom.xml file

```xml
<!-- https://mvnrepository.com/artifact/ch.regdata.rps.engine.client/rps-engine-api-client -->
<dependency>
    <groupId>ch.regdata.rps.engine.client</groupId>
    <artifactId>rps-engine-api-client</artifactId>
    <version>3.10.0</version>
</dependency>
```

## Usage

### Initialization

First initialize `EngineProviderFactory` class with the apiKey and secretKey you got from [RPS CoreConfiguration](https://rpsprod.ch)

```java
package ch.regdata.rps.engine.client.examples;

import ch.regdata.rps.engine.client.http.HttpClientEngineProvider;

public abstract class EngineProviderFactory {
public static HttpClientEngineProvider getEngineProvider() {
// Configuration options for RPS Engine Client library.
final String engineHostName = "https://engine.rpsprod.ch";// Example: "https://engine.rpsprod.ch"
final String identityHostName = "https://identity.rpsprod.ch";  // Example: "https://identity.rpsprod.ch",
final String apiKey = "****************"; // Example: "518155d0-4774-467c-b891-6ebd3ea07f08"
final String secretKey = "*****************"; // Example: "75582a544bdasa23easdasdaadssd5f60369b9866026d8485aa49f7edcfe986bb1"

        // Set the engine provider with connection details
        HttpClientEngineProvider engineProvider = new HttpClientEngineProvider();
        engineProvider.setApiKey(apiKey);
        engineProvider.setSecretKey(secretKey);
        engineProvider.setIdentityHost(identityHostName);
        engineProvider.setEngineHost(engineHostName);

        return engineProvider;
    }
}
```

### Simple example

Then do transformations as in the following `SimpleUsageExample` class


```java
package your package;

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

```

### Using secrets managers

Each request can optionally target a specific secrets manager, identified by its `UUID`. The secrets manager is
passed as the last parameter (after the logging context) of `RequestContext.withRequest(...)` and
`RPSEngine.transform(...)`. Requests combined in the same `RequestContext` may use different secrets managers and are
still sent to the RPS Engine API in a single call. When no secrets manager is given (`null`), the property is not sent
and the default behaviour applies.

```java
RequestContext requestContext = engine
        .createContext()
        .withRequest(new IRPSValue[]{firstName}, adminRightsContext, protectProcessingContext, null, firstSecretsManager)
        .withRequest(new IRPSValue[]{lastName}, adminRightsContext, protectProcessingContext, null, secondSecretsManager);
requestContext.transform();

// Single request shortcut
engine.transform(new IRPSValue[]{firstName}, "Admin", "Protect", null, firstSecretsManager);
```

See `UsageWithSecretsManagerExample` for a complete example.
