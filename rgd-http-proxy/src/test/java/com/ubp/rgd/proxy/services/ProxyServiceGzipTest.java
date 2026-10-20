package com.ubp.rgd.proxy.services;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for GZIP response-body handling in {@link ProxyService#gunzipIfNeeded(byte[])}.
 */
class ProxyServiceGzipTest {

    private static byte[] gzip(String text) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(baos)) {
            gzip.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return baos.toByteArray();
    }

    @Test
    void testGzippedBodyIsDecompressed() throws Exception {
        String json = "{\"firstName\":\"John\",\"lastName\":\"Doe\"}";
        byte[] compressed = gzip(json);

        // Sanity: the compressed bytes carry the gzip magic number.
        assertEquals((byte) 0x1f, compressed[0]);
        assertEquals((byte) 0x8b, compressed[1]);

        byte[] result = ProxyService.gunzipIfNeeded(compressed);

        assertEquals(json, new String(result, StandardCharsets.UTF_8));
    }

    @Test
    void testPlainBodyIsReturnedUnchanged() {
        byte[] plain = "{\"plain\":true}".getBytes(StandardCharsets.UTF_8);

        byte[] result = ProxyService.gunzipIfNeeded(plain);

        assertArrayEquals(plain, result);
    }

    @Test
    void testNullIsReturnedUnchanged() {
        assertNull(ProxyService.gunzipIfNeeded(null));
    }

    @Test
    void testTooShortBodyIsReturnedUnchanged() {
        byte[] oneByte = new byte[]{(byte) 0x1f};
        assertArrayEquals(oneByte, ProxyService.gunzipIfNeeded(oneByte));
    }

    @Test
    void testGzipMagicButInvalidPayloadFallsBackToRawBytes() {
        // Starts with the gzip magic but is not a valid gzip stream -> must not throw, returns raw.
        byte[] fakeGzip = new byte[]{(byte) 0x1f, (byte) 0x8b, 0x00, 0x01, 0x02, 0x03};
        assertArrayEquals(fakeGzip, ProxyService.gunzipIfNeeded(fakeGzip));
    }
}
