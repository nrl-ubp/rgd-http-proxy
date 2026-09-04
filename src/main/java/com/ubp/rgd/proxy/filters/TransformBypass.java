package com.ubp.rgd.proxy.filters;

import jakarta.ws.rs.container.ContainerRequestContext;

/**
 * Reads the {@value #HEADER_NAME} request header, through which a caller asks the proxy to forward a
 * request <b>without any RPS transformation</b>, whatever the endpoint transform configuration says.
 * <p>
 * The header is honoured by {@link PreFilter} and {@link PostFilter} only, and only when
 * {@code proxy.transform.allow-ignore-header} is enabled. {@link PostFilter} reads it from the
 * <b>request</b> too: the response is never inspected for it.
 * <p>
 * Stateless on purpose, so that both {@code @Provider} filters can use it without an extra bean.
 */
public final class TransformBypass {

    /** Request header asking the proxy not to transform the payloads of that request. */
    public static final String HEADER_NAME = "X-Proxy-Ignore-Transform";

    /** The only value activating the bypass, compared ignoring case and surrounding whitespace. */
    private static final String ENABLED_VALUE = "true";

    private TransformBypass() {
    }

    /**
     * Tell whether the caller sent the bypass header, whatever its value. Used to reject a request
     * asking for a bypass while the feature is disabled, instead of silently transforming it.
     *
     * @param requestContext the request to inspect
     * @return {@code true} when the header is present, even empty
     */
    public static boolean isPresent(ContainerRequestContext requestContext) {
        return requestContext != null && requestContext.getHeaderString(HEADER_NAME) != null;
    }

    /**
     * Tell whether the caller asked for the RPS transformation to be skipped.
     *
     * @param requestContext the request to inspect
     * @return {@code true} when the header holds {@code true}, ignoring case and surrounding
     *         whitespace; {@code false} for any other value and when the header is absent
     */
    public static boolean isRequested(ContainerRequestContext requestContext) {
        if (requestContext == null) {
            return false;
        }
        String value = requestContext.getHeaderString(HEADER_NAME);
        return value != null && ENABLED_VALUE.equalsIgnoreCase(value.trim());
    }
}
