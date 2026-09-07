package com.ubp.rgd.proxy.exception;

import com.ubp.rgd.proxy.transform.RPSTransformException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * This Exception Mapper will send appropriated Response to API clients when RPSTransformException is thrown
 */
@Provider
@ApplicationScoped
public class RPSTransformExceptionMapper implements ExceptionMapper<RPSTransformException> {
    private static final Logger LOG = LoggerFactory.getLogger(RPSTransformExceptionMapper.class);
    @Override
    public Response toResponse(RPSTransformException ex) {
        LOG.error("RPS Transform error", ex);
        return Response
                .status(Response.Status.BAD_REQUEST) // or NOT_FOUND if appropriate
                .entity(String.format("RPS Transform error: %s", ex.getMessage()))
                .build();
    }
}
