package com.ubp.rgd.proxy.fuzzysearch.jpa;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CDI producer that bootstraps the EclipseLink {@link EntityManagerFactory}.
 *
 * <p>The Quarkus Agroal datasource {@value #DATASOURCE} (configured in the
 * proxy's {@code application.properties}) is passed to EclipseLink as the
 * non-JTA datasource so that the connection pool is fully managed by Quarkus /
 * Agroal. The factory is only built on first use, so a disabled fuzzy search
 * never touches the database: its datasource is then inactive.
 *
 * <p>Transaction management is RESOURCE_LOCAL; service methods open an
 * {@code EntityManager} per call and handle {@code EntityTransaction}
 * begin/commit/rollback explicitly.
 */
@ApplicationScoped
public class EntityManagerProducer {

    private static final Logger LOG = LoggerFactory.getLogger(EntityManagerProducer.class);

    // EclipseLink property keys (string literals avoid the module-export issue with
    // org.eclipse.persistence.config.PersistenceUnitProperties at compile time)
    private static final String PROP_NON_JTA_DS       = "jakarta.persistence.nonJtaDataSource";
    private static final String PROP_CACHE_SHARED_DEF  = "eclipselink.cache.shared.default";

    /** Name of the Quarkus datasource holding the PERSON table. */
    public static final String DATASOURCE = "fuzzy-search";

    /** Name of the persistence unit of {@code META-INF/persistence.xml}. */
    static final String PERSISTENCE_UNIT = "fuzzy-search";

    /** Looked up on first use only: the datasource is inactive while the fuzzy search is disabled. */
    @Inject
    @DataSource(DATASOURCE)
    Instance<AgroalDataSource> dataSource;

    /**
     * Database kind from Quarkus datasource configuration.
     * Drives the EclipseLink {@code target-database} dialect at runtime.
     *
     * <p>Supported values:
     * <ul>
     *   <li>{@code mssql}      → EclipseLink {@code SQLServer} (built in)</li>
     *   <li>{@code postgresql} → EclipseLink {@code PostgreSQL}</li>
     *   <li>{@code oracle}     → EclipseLink {@code Oracle}</li>
     * </ul>
     *
     * @see <a href="https://eclipse.dev/eclipselink/api/3.0/org/eclipse/persistence/config/TargetDatabase.html">
     *      EclipseLink TargetDatabase</a>
     */
    @ConfigProperty(name = "quarkus.datasource.\"" + DATASOURCE + "\".db-kind", defaultValue = "mssql")
    String dbKind;

    /**
     * Default schema applied to every JPA entity whose {@code @Table}
     * annotation does not explicitly set {@code schema = "..."}.
     *
     * <p>EclipseLink reads this via the {@code eclipselink.default-schema}
     * property, which is set programmatically when the factory is built.
     *
     * <p>Examples:
     * <ul>
     *   <li>PostgreSQL – {@code public} (the standard default schema)</li>
     *   <li>Oracle – the schema owner, e.g. {@code TOKEN_FUZZY_SEARCH}</li>
     *   <li>Leave blank ({@code ""}) to let EclipseLink use the connection's
     *       default schema without any explicit prefix.</li>
     * </ul>
     */
    @ConfigProperty(name = "fuzzy-search.jpa.default-schema", defaultValue = "")
    String defaultSchema;

    private EntityManagerFactory emf;

    /** Builds the factory, on first use of the producer. */
    private synchronized EntityManagerFactory factory() {
        if (emf == null) {
            emf = build();
        }
        return emf;
    }

    private EntityManagerFactory build() {
        LOG.info("Bootstrapping EclipseLink EntityManagerFactory (db-kind=" + dbKind + ")...");

        Map<String, Object> props = new HashMap<>();

        // Hand our Agroal-managed datasource to EclipseLink
        props.put(PROP_NON_JTA_DS, dataSource.get());

        // Disable second-level cache sharing – avoids stale-data issues on hot-reload
        props.put(PROP_CACHE_SHARED_DEF, "false");

        // Resolve EclipseLink target-database from quarkus.datasource.db-kind.
        String targetDatabase = resolveEclipseLinkDialect(dbKind);
        props.put("eclipselink.target-database", targetDatabase);
        LOG.info("EclipseLink target-database: " + targetDatabase);

        // Set the default schema for all entities that do not specify schema= in @Table.
        // An empty value means "use the connection's default schema".
        if (defaultSchema != null && !defaultSchema.isBlank()) {
            props.put("eclipselink.default-schema", defaultSchema.trim());
            LOG.info("EclipseLink default-schema: " + defaultSchema.trim());
        } else {
            LOG.info("EclipseLink default-schema: (none – using connection default)");
        }

        EntityManagerFactory factory = Persistence.createEntityManagerFactory(PERSISTENCE_UNIT, props);
        LOG.info("EclipseLink EntityManagerFactory ready.");
        return factory;
    }

    /**
     * Maps a Quarkus {@code quarkus.datasource.db-kind} value to the
     * corresponding EclipseLink {@code target-database} string.
     *
     * @param kind value of {@code quarkus.datasource.db-kind} (case-insensitive)
     * @return EclipseLink target-database name
     */
    private static String resolveEclipseLinkDialect(String kind) {
        if (kind == null) return "SQLServer";
        return switch (kind.toLowerCase().trim()) {
            case "oracle" -> "Oracle";
            case "postgresql", "postgres" -> "PostgreSQL";
            case "mssql", "sqlserver" -> "SQLServer";
            default -> {
                LOG.warn("Unknown db-kind='" + kind + "' of the " + DATASOURCE
                        + " datasource. Defaulting to the SQLServer dialect.");
                yield "SQLServer";
            }
        };
    }

    /**
     * Produces an application-scoped {@link EntityManagerFactory}.
     * Consumers should call {@code emf.createEntityManager()} themselves
     * (or inject via the service layer).
     */
    @Produces
    @ApplicationScoped
    @FuzzySearchPersistence
    public EntityManagerFactory entityManagerFactory() {
        return factory();
    }

    /**
     * Produces a request-scoped {@link EntityManager}.
     * One EM is created per HTTP request and closed automatically when the
     * request scope is destroyed (via the {@link #closeEntityManager} disposer).
     *
     * <p>Note: the EM operates in RESOURCE_LOCAL mode. REST endpoints that use
     * {@code @Transactional} coordinate the begin/commit at the CDI level while
     * the EM handles the actual SQL; for full JTA integration, switch the
     * persistence unit to {@code transaction-type="JTA"}.
     */
    @Produces
    @RequestScoped
    @FuzzySearchPersistence
    public EntityManager entityManager() {
        return factory().createEntityManager();
    }

    void closeEntityManager(@Disposes @FuzzySearchPersistence EntityManager em) {
        if (em.isOpen()) {
            em.close();
        }
    }

    @PreDestroy
    void close() {
        if (emf != null && emf.isOpen()) {
            emf.close();
            LOG.info("EclipseLink EntityManagerFactory closed.");
        }
    }
}
