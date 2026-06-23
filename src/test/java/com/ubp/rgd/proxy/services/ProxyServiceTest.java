package com.ubp.rgd.proxy.services;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.ubp.rgd.proxy.services.wdx1.WDX1ConcatRequest;
import com.ubp.rgd.proxy.services.wdx1.WDX1ConcatResponse;
import com.ubp.rgd.proxy.utils.JSONFile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.http.Header;
import io.restassured.http.Headers;
import io.restassured.response.Response;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.io.IOException;
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

    private static final Logger LOG = Logger.getLogger(ProxyServiceTest.class);

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
        LOG.infof("GET response body:\n%s", responseBody);

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
        LOG.infof("POST response body:\n%s", unprotectedBody);

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
        concatReq.setBirthDate(concatReq.dateFormat.format(person.birthDate));
        concatReq.setCountry("CH");

        LOG.infof("Concat request is:\n%s", concatReq.toJSONString());

        response = RestAssured.given()
                .contentType(ContentType.JSON)
                .body(concatReq)
                .when()
                .post(proxyConcatUrl);

        assertNotNull(response);
        response.then().statusCode(200);

        String concatBody = response.getBody().asString();
        LOG.infof("CONCAT response body is: %s", concatBody);

        WDX1ConcatResponse concatResponse = JSONFile.parse(concatBody, WDX1ConcatResponse.class);
        assertNotNull(concatResponse);
        String respToken = concatResponse.getConcatResponseToken();
        assertNotNull(respToken);
        assertFalse(respToken.isEmpty());
    }
}