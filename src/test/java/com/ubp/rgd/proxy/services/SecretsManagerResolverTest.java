package com.ubp.rgd.proxy.services;

import com.ubp.rgd.proxy.exception.UnknownSecretsManagerException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.ubp.rgd.proxy.services.TestSecretsManagers.CH;
import static com.ubp.rgd.proxy.services.TestSecretsManagers.HEADER;
import static com.ubp.rgd.proxy.services.TestSecretsManagers.LU;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SecretsManagerResolverTest {

    @TempDir
    Path dir;

    private final SecretsManagerResolver resolver = TestSecretsManagers.resolver();

    @Test
    @DisplayName("No header, or a blank one, selects the default mapping")
    void defaultWithoutHeader() {
        assertEquals(LU, resolver.resolve((String) null));
        assertEquals(LU, resolver.resolve("  "));
        assertEquals(LU, resolver.defaultSecretsManager());
    }

    @Test
    @DisplayName("Header values are matched ignoring case and surrounding blanks")
    void caseInsensitive() {
        assertEquals(CH, resolver.resolve("CH"));
        assertEquals(CH, resolver.resolve("ch"));
        assertEquals(CH, resolver.resolve(" Ch "));
        assertEquals(LU, resolver.resolve("lu"));
    }

    @Test
    @DisplayName("An unknown value is a 400")
    void unknownValue() {
        UnknownSecretsManagerException e =
                assertThrows(UnknownSecretsManagerException.class, () -> resolver.resolve("MC"));
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), e.getResponse().getStatus());
        assertTrue(e.getMessage().contains(HEADER), e.getMessage());
        assertTrue(e.getMessage().contains("MC"), e.getMessage());
    }

    @Test
    @DisplayName("The header is read from the JAX-RS headers and from the request context")
    void readsTheConfiguredHeader() {
        HttpHeaders headers = mock(HttpHeaders.class);
        when(headers.getHeaderString(HEADER)).thenReturn("CH");
        assertEquals(CH, resolver.resolve(headers));
        assertEquals(LU, resolver.resolve((HttpHeaders) null));

        ContainerRequestContext context = mock(ContainerRequestContext.class);
        when(context.getHeaderString(HEADER)).thenReturn("ch");
        assertEquals(CH, resolver.resolve(context));
        assertEquals(HEADER, resolver.headerName());
    }

    @Test
    @DisplayName("The shipped mapping file is valid")
    void shippedFileIsValid() {
        SecretsManagerResolver shipped = TestSecretsManagers.resolver("./config/secrets_manager_mapping.json");
        assertEquals(shipped.resolve("LU"), shipped.defaultSecretsManager());
    }

    @Test
    void failsOnMissingFile() {
        assertStartupFailure(dir.resolve("absent.json").toString(), "does not exist");
    }

    @Test
    void failsOnUnreadableJson() throws IOException {
        assertStartupFailure(write("{ not json"), "cannot be read");
    }

    @Test
    void failsOnIdThatIsNotAUuid() throws IOException {
        assertStartupFailure(write("""
                {"default-mapping":"LU","mappings":[{"LU":"12345-56789-44567"}]}"""), "not a UUID");
    }

    @Test
    void failsWithoutDefault() throws IOException {
        assertStartupFailure(write("""
                {"mappings":[{"LU":"b9f72aef-6c1b-4556-bf65-9813f122cf8b"}]}"""), "no 'default-mapping'");
    }

    @Test
    void failsOnUnknownDefault() throws IOException {
        assertStartupFailure(write("""
                {"default-mapping":"CH","mappings":[{"LU":"b9f72aef-6c1b-4556-bf65-9813f122cf8b"}]}"""),
                "is not one of its mappings");
    }

    @Test
    void failsOnDuplicateKeyIgnoringCase() throws IOException {
        assertStartupFailure(write("""
                {"default-mapping":"LU","mappings":[
                  {"LU":"b9f72aef-6c1b-4556-bf65-9813f122cf8b"},
                  {"lu":"16ec8462-e8d5-4a2c-b8df-f253e09bd274"}]}"""), "more than once");
    }

    @Test
    void failsOnBlankKey() throws IOException {
        assertStartupFailure(write("""
                {"default-mapping":"LU","mappings":[
                  {"LU":"b9f72aef-6c1b-4556-bf65-9813f122cf8b"},
                  {" ":"16ec8462-e8d5-4a2c-b8df-f253e09bd274"}]}"""), "blank key");
    }

    @Test
    void defaultMatchedIgnoringCase() throws IOException {
        SecretsManagerResolver lower = TestSecretsManagers.resolver(write("""
                {"default-mapping":"lu","mappings":[{"LU":"b9f72aef-6c1b-4556-bf65-9813f122cf8b"}]}"""));
        assertEquals(LU, lower.defaultSecretsManager());
    }

    private String write(String json) throws IOException {
        Path file = Files.createTempFile(dir, "mapping", ".json");
        Files.writeString(file, json);
        return file.toString();
    }

    private static void assertStartupFailure(String file, String expected) {
        IllegalStateException e =
                assertThrows(IllegalStateException.class, () -> TestSecretsManagers.resolver(file));
        assertTrue(e.getMessage().contains(expected), e.getMessage());
    }
}
