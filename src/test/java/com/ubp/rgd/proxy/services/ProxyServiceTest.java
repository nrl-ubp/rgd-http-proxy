package com.ubp.rgd.proxy.services;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.ubp.rgd.proxy.services.wdx1.WDX1ConcatRequest;
import com.ubp.rgd.proxy.services.wdx1.WDX1ConcatResponse;
import com.ubp.rgd.proxy.tools.FileTransformTriggerApp;
import com.ubp.rgd.proxy.transform.config.FileTransformConfig;
import com.ubp.rgd.proxy.utils.JSONFile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.http.Header;
import io.restassured.http.Headers;
import io.restassured.response.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * This is a quarkus test : its initialise and loads the quarkus application before launching the test suite.
 * Quarkus objects can be mocked using @InjectMock annotation but they are NOT initialised with properties.
 * To configure quarkus application, use the %test profile override in application.properties AND use the classical
 * <code>@Inject</code> annotation. Additional properties override is provided by the TestProfile that is loaded before hand this
 * test class.
 * To test transformation of a sample json structure containing sensitive data, a sample web service is created before
 * tests runs. It exposes a sample functional service providing sensitive data. Protection is done with the
 * proxy quarkus server mounted by the test. The proxy intercept payloads of certain endpoints defined in the
 * rps_transform_config.json file.
 *
 */
@QuarkusTest
@TestProfile(com.ubp.rgd.proxy.services.TestProfile.class)
class ProxyServiceTest {

    private static final Logger LOG = LoggerFactory.getLogger(ProxyServiceTest.class);

    public static final String apiPathUrl = "/api/v1/persons/1";
    public static final String proxyPathUrl = String.format("/proxy%s", apiPathUrl);
    public static final String proxyConcatUrl = "/utils/wdx1/concat";

    private static HttpServer server;

    /**
     * JSON object mapper is used across the tests
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeAll
    public static void setup() throws IOException, InterruptedException {
        server = PersonServer.create(apiPathUrl);

        // Start the server
        server.setExecutor(null); // Use the default executor
        server.start();
        LOG.info("TEST Mocking Server is running on http://localhost:37200/");

        // ensure we are ready before launching test suite against this test server.
        Thread.sleep(500);
    }

    @AfterAll
    public static void tearDown() {
        LOG.info("Stopping TEST Mocking server");
        server.stop(0);
    }

    /**
     * Get the contents of person.json without tokenization
     * @return List of Person objects
     */
    private List<Person> getClearPersonsFromFile() throws IOException {

        byte[] clearContents = Person.getPersonsFileContents();
        return objectMapper.readValue(new String(clearContents), new TypeReference<>() {  } );
    }

    @Test
    public void testProxyService() throws IOException {

        List<Person> originalPersons = getClearPersonsFromFile();

        // note that we set two times the same header on purpose
        Header headerToProtect = new Header("X-TRANSFORM-HEADER", "Clint Eastwood");
        Header headerToProtectAgain = new Header("X-TRANSFORM-HEADER", "George Clooney");
        Header headerNotToProtect = new Header("X-SOME-HEADER", "Bayerische Motoren Werke");
        Headers clearHeaders = new Headers(headerToProtect, headerToProtectAgain, headerNotToProtect);

        // send a non authenticated request. see the TestProfile class to activate Kerberos support
        Response response = RestAssured.given()
                .headers(clearHeaders)
                .when()
                .get(proxyPathUrl);

        response.then().statusCode(200);

        String responseBody = response.getBody().asString();
        Headers headers = response.getHeaders();
        assertNotNull(responseBody);

        // display headers and body after transformation
        headers.asList().stream().map(header -> String.format("GET Response Header: %s = %s", header.getName(), header.getValue())).forEach(LOG::info);
        LOG.info("GET response body:\n{}", responseBody);

        // after a protection, persons should be different
        List<Person> protectedPersons = objectMapper.readValue(responseBody, new TypeReference<>() { } );
        Person clearPerson = originalPersons.getFirst();
        Person protectedPerson = protectedPersons.getFirst();
        assertNotEquals(clearPerson, protectedPerson);

        // now if I do a POST, then the answer should be the same as original since unprotected.
        response = RestAssured.given()
                .contentType(ContentType.JSON)
                .body(responseBody)
                .when()
                .post(proxyPathUrl);

        String unprotectedBody = response.getBody().asString();
        headers = response.getHeaders();
        List<Person> unprotectedPersons = objectMapper.readValue(unprotectedBody, new TypeReference<>() {
        } );
        Person unprotectedPerson = unprotectedPersons.getFirst();

        headers.asList().stream().map(header -> String.format("POST Response Header: %s = %s", header.getName(), header.getValue())).forEach(LOG::info);
        LOG.info("POST response body:\n{}", unprotectedBody);

        // back to unprotected state then should be the same as clear text
        assertEquals(clearPerson, unprotectedPerson);
    }

    /**
     * Principles of the WDX1 CONCAT operator test: get a tokenized person from the random person server.
     * Build a CONCAT request and send it to CONCAT operator endpoint. Verify that we get a token in CONCAT response
     * format.
     * @throws IOException in case of any problem
     */
    @Test
    public void testWdx1UtilsService() throws IOException {
        // send a non authenticated request. see the TestProfile class to activate Kerberos support
        Response response = RestAssured.given()
                .when()
                .get(proxyPathUrl);

        response.then().statusCode(200);

        String responseBody = response.getBody().asString();
        Headers headers = response.getHeaders();
        assertNotNull(responseBody);

        // get the protected person object
        List<Person> protectedPersons = objectMapper.readValue(responseBody, new TypeReference<>() { } );
        Person person = protectedPersons.getFirst();

        assertNotNull(person);

        WDX1ConcatRequest concatReq = new WDX1ConcatRequest();
        concatReq.setFirstName(person.firstName);
        concatReq.setLastName(person.lastName);

        SimpleDateFormat df = new SimpleDateFormat(concatReq.getDateFormat());

        concatReq.setBirthDate(df.format(person.birthDate));
        concatReq.setCountry("CH");

        LOG.info("Concat request is:\n{}", concatReq.toJSONString());

        response = RestAssured.given()
                .contentType(ContentType.JSON)
                .body(concatReq)
                .when()
                .post(proxyConcatUrl);

        assertNotNull(response);
        response.then().statusCode(200);

        String concatBody = response.getBody().asString();
        LOG.info("CONCAT response body is: {}", concatBody);

        WDX1ConcatResponse concatResponse = JSONFile.parse(concatBody, WDX1ConcatResponse.class);
        assertNotNull(concatResponse);
        String respToken = concatResponse.getConcatResponseToken();
        assertNotNull(respToken);
        assertFalse(respToken.isEmpty());
    }

    /**
     * Drives {@link FileTransformTriggerApp} against the Quarkus server started by this test, to
     * check that the app really triggers a transformation rather than merely reading a configuration.
     * <p>
     * The app is called through {@code run(...)} and not {@code main(...)}: {@code main} ends with
     * {@code System.exit}, which would tear down the surefire JVM and lose the results of the whole
     * test class.
     */
    @Test
    public void testTriggerFileProtection() throws Exception {
        FileTransformConfig config = fileTransformConfig("protect_random_persons");

        Path sourceDir = Paths.get(config.getSourceDirectory());
        Path targetDir = Paths.get(config.getTargetDirectory());
        Files.createDirectories(sourceDir);
        Files.createDirectories(targetDir);
        Files.createDirectories(Paths.get(config.getErrorDirectory()));

        // A fixture of our own, so the test proves a transformation happened instead of depending on
        // whatever files may or may not be sitting in the source directory.
        String fixtureName = "trigger-test-" + System.currentTimeMillis() + ".json";
        Path fixture = sourceDir.resolve(fixtureName);
        Files.writeString(fixture, "[{\"LongName\":\"Jean-Claude DUSSE\",\"ShortName\":\"JCD\"}]");

        // The Quarkus test server does not listen on quarkus.http.port but on quarkus.http.test-port,
        // which RestAssured is configured with.
        String hostPort = "localhost:" + RestAssured.port;
        LOG.info("Triggering file transformation on {}", hostPort);

        int exitCode = FileTransformTriggerApp.run(new String[]{"protect_random_persons", hostPort});
        assertEquals(0, exitCode, "The trigger app should report a successful transformation");

        assertFalse(Files.exists(fixture), "The source file should have been moved out of the source directory");

        Path transformed = targetDir.resolve(fixtureName);
        assertTrue(Files.exists(transformed), "The transformed file should be in the target directory");

        String content = Files.readString(transformed);
        LOG.info("Transformed content: {}", content);
        assertFalse(content.contains("Jean-Claude DUSSE"), "The clear value should not remain in the target file");
        assertTrue(content.contains("RG{"), "The target file should hold tokens");

        Files.deleteIfExists(transformed);
    }

    /**
     * A batch step must not report success when the server could not transform the files, which is
     * what used to happen: a failed file was counted as processed and the run reported "success".
     */
    @Test
    public void testTriggerFileProtectionFailureIsReported() throws Exception {
        FileTransformConfig config = fileTransformConfig("protect_random_persons");

        Path sourceDir = Paths.get(config.getSourceDirectory());
        Path errorDir = Paths.get(config.getErrorDirectory());
        Files.createDirectories(sourceDir);
        Files.createDirectories(errorDir);

        String fixtureName = "trigger-broken-" + System.currentTimeMillis() + ".json";
        Path fixture = sourceDir.resolve(fixtureName);
        Files.writeString(fixture, "{ this is not json");

        int exitCode = FileTransformTriggerApp.run(
                new String[]{"protect_random_persons", "localhost:" + RestAssured.port});

        assertNotEquals(0, exitCode, "A run that could not transform its file must not report success");

        Path failed = errorDir.resolve(fixtureName);
        assertTrue(Files.exists(failed), "The failed file should be in the error directory");

        Files.deleteIfExists(failed);
    }

    /**
     * A configuration polled by the server cannot be driven by a batch as well, otherwise the two
     * would process the same directory at the same time.
     */
    @Test
    public void testTriggerRefusesAScheduledConfiguration() throws Exception {
        int exitCode = FileTransformTriggerApp.run(
                new String[]{"scheduled_persons", "localhost:" + RestAssured.port});

        assertEquals(7, exitCode, "A scheduled configuration should be refused");
    }

    /**
     * Read a configuration from the same file the server is configured with, to keep the test and the
     * server in agreement about the directories being used.
     */
    private FileTransformConfig fileTransformConfig(String name) throws IOException {
        List<FileTransformConfig> configs = objectMapper.readValue(
                new File("./src/test/resources/file_transform_config.json"), new TypeReference<>() {});

        return configs.stream()
                .filter(cfg -> cfg.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Test configuration not found: " + name));
    }
}