package com.ubp.rgd.proxy.resources;

import com.ubp.rgd.proxy.services.TestSecretsManagers;
import com.ubp.rgd.proxy.services.wdx1.WDX1ConcatRequest;
import com.ubp.rgd.proxy.services.wdx1.WDX1UtilsService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Checks that {@code /utils/wdx1/concat} tokenizes with the secrets manager named by the jurisdiction
 * header, and that the header is declared in the OpenAPI document used by the Swagger UI.
 */
@QuarkusTest
@TestProfile(com.ubp.rgd.proxy.services.TestProfile.class)
class Wdx1ConcatSecretsManagerHeaderTest {

    private static final String CONCAT = "/utils/wdx1/concat";

    @InjectMock
    WDX1UtilsService wdx1UtilsService;

    @BeforeEach
    void stubService() throws Exception {
        when(wdx1UtilsService.tokenConcat(any(WDX1ConcatRequest.class), any(UUID.class))).thenReturn("RG{CONCAT}");
    }

    @Test
    @DisplayName("The jurisdiction header selects the secrets manager, case-insensitively")
    void headerSelectsTheSecretsManager() throws Exception {
        concat("CH");
        verify(wdx1UtilsService).tokenConcat(any(WDX1ConcatRequest.class), eq(TestSecretsManagers.CH));

        concat("lu");
        verify(wdx1UtilsService).tokenConcat(any(WDX1ConcatRequest.class), eq(TestSecretsManagers.LU));
    }

    @Test
    @DisplayName("Without the header, the default mapping is used")
    void noHeaderUsesTheDefault() throws Exception {
        RestAssured.given()
                .contentType(ContentType.JSON)
                .body("{}")
                .post(CONCAT)
                .then()
                .statusCode(200)
                .body("concatToken", equalTo("RG{CONCAT}"));
        verify(wdx1UtilsService).tokenConcat(any(WDX1ConcatRequest.class), eq(TestSecretsManagers.LU));
    }

    @Test
    @DisplayName("The OpenAPI document declares the header on the concat operation only")
    void headerIsInTheOpenApiDocument() {
        JsonPath openApi = RestAssured.given()
                .accept(ContentType.JSON)
                .get("/openapi?format=json")
                .then()
                .statusCode(200)
                .extract().jsonPath();

        List<Map<String, Object>> parameters = openApi.getList("paths.'" + CONCAT + "'.post.parameters");
        assertNotNull(parameters, "the concat operation has no parameter");
        Map<String, Object> header = parameters.stream()
                .filter(p -> TestSecretsManagers.HEADER.equals(p.get("name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + TestSecretsManagers.HEADER + " parameter"));

        assertEquals("header", header.get("in"));
        assertFalse(Boolean.TRUE.equals(header.get("required")));
        assertEquals("string", ((Map<?, ?>) header.get("schema")).get("type"));
        String description = (String) header.get("description");
        assertTrue(description.contains("CH, LU"), description);
        assertTrue(description.contains("Left empty, LU is used"), description);

        List<Map<String, Object>> transformParameters = openApi.getList("paths.'/transform'.post.parameters");
        assertTrue(transformParameters == null || transformParameters.stream()
                .noneMatch(p -> TestSecretsManagers.HEADER.equals(p.get("name"))));
    }

    private void concat(String jurisdiction) {
        RestAssured.given()
                .header(TestSecretsManagers.HEADER, jurisdiction)
                .contentType(ContentType.JSON)
                .body("{}")
                .post(CONCAT)
                .then()
                .statusCode(200);
    }
}
