package com.ubp.rgd.proxy.exception;

import com.ubp.rgd.proxy.transform.TransformException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * This Exception Mapper will send appropriated Response to API clients when TransformException is thrown
 */
@Provider
@ApplicationScoped
public class TransformExceptionMapper implements ExceptionMapper<TransformException> {
    private static final Logger LOG = LoggerFactory.getLogger(TransformExceptionMapper.class);
    @Override
    public Response toResponse(TransformException ex) {
        LOG.error("Transform error", ex);
        return Response
                .status(Response.Status.BAD_REQUEST) // or NOT_FOUND if appropriate
                .entity(String.format("Transform error: %s", ex.getMessage()))
                .build();
    }
}
