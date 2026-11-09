package com.ubp.rgd.proxy.flightsql;

import com.ubp.rgd.proxy.sqlgateway.PhaseContexts;
import com.ubp.rgd.proxy.sqlgateway.SqlTransformRewriter;
import com.ubp.rgd.proxy.sqlgateway.config.PhaseConfig;
import com.ubp.rgd.proxy.transform.EndPointTransformer;
import com.ubp.rgd.proxy.transform.TransformException;
import com.ubp.rgd.proxy.transform.model.MappedValue;
import com.ubp.rgd.proxy.transform.model.ProcessingContext;
import com.ubp.rgd.proxy.transform.model.RightContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code transform()} and {@code transform_search()} SQL extensions of the Flight SQL server,
 * applied to a statement <b>before</b> it is sent to the database server, with the contexts of the
 * {@code before} phase of {@code proxy.flight-sql.mapping-config-file}.
 * <p>
 * The rewriting itself is the {@link SqlTransformRewriter} shared with the TDS gateway, which
 * documents the syntax.
 */
@ApplicationScoped
public class FlightSqlTokenizeService {

    private static final Logger LOG = LoggerFactory.getLogger(FlightSqlTokenizeService.class);

    private static final SqlTransformRewriter REWRITER = new SqlTransformRewriter("Flight SQL");

    @Inject
    EndPointTransformer transformer;

    @Inject
    FlightSqlMappingConfigProvider mappingConfigProvider;

    /**
     * Replace every {@code transform(...)} and {@code transform_search(...)} call of a statement.
     *
     * @param sql the statement as the client sent it
     * @return the statement to send to the database server
     * @throws SqlTransformRewriter.SqlTransformSyntaxException when a call is malformed, or when the
     *         {@code before} phase is not active
     * @throws TransformException when the engine fails to transform the values
     */
    public String rewrite(String sql) throws TransformException {
        return REWRITER.rewrite(sql, beforePhase(), (values, right, processing) -> transformValues(values));
    }

    /**
     * Hand the values over to the engine, which replaces their transformed value in place. Extracted
     * so that it can be substituted in the tests.
     *
     * @param values one value per {@code transform()} call of the statement
     */
    protected void transformValues(MappedValue[] values) throws TransformException {
        try {
            transformer.transformData(values, buildRightContext(), buildProcessingContext());
        } catch (Exception e) {
            LOG.error("Failed to transform the extension calls of the statement", e);
            throw new TransformException(e);
        }
    }

    /**
     * The phase that applies to a statement on its way to the database server.
     *
     * @return the configured {@code before} phase, never null
     */
    private PhaseConfig beforePhase() {
        PhaseConfig phase = mappingConfigProvider.get().getBefore();
        return phase == null ? new PhaseConfig() : phase;
    }

    RightContext buildRightContext() {
        return PhaseContexts.rightContext(beforePhase());
    }

    ProcessingContext buildProcessingContext() {
        return PhaseContexts.processingContext(beforePhase(), "Protect", "Flight SQL 'before'");
    }

    /** For the tests, which build the service outside of CDI. */
    public void setTransformer(EndPointTransformer transformer) {
        this.transformer = transformer;
    }

    /** For the tests, which build the service outside of CDI. */
    public void setMappingConfigProvider(FlightSqlMappingConfigProvider mappingConfigProvider) {
        this.mappingConfigProvider = mappingConfigProvider;
    }
}
