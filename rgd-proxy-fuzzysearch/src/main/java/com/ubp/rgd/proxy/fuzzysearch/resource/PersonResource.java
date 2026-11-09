package com.ubp.rgd.proxy.fuzzysearch.resource;

import com.ubp.rgd.proxy.fuzzysearch.FuzzySearch;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import com.ubp.rgd.proxy.fuzzysearch.jpa.FuzzySearchPersistence;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import com.ubp.rgd.proxy.fuzzysearch.dto.PersonCreateRequest;
import com.ubp.rgd.proxy.fuzzysearch.dto.PersonResponse;
import com.ubp.rgd.proxy.fuzzysearch.entity.PersonEntity;
import com.ubp.rgd.proxy.fuzzysearch.service.search.PersonIndexingService;
import com.ubp.rgd.proxy.fuzzysearch.service.protection.ProtectionService;
import com.ubp.rgd.proxy.fuzzysearch.service.protection.ProtectionService.ColumnValue;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;

@Path(FuzzySearch.BASE_PATH + "/persons")
@FuzzySearchEndpoint
@Tag(name = FuzzySearch.TAG, description = "Fuzzy person search over the protected PERSON table")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class PersonResource {

    @Inject
    @FuzzySearchPersistence
    EntityManager em;

    @Inject
    ProtectionService protectionService;

    /**
     * Shared indexing service — same instance used by {@link AdminResource#reindex}.
     * Injecting it here guarantees that exactly the same signature algorithm
     * (FastText hybrid search) is used whether a person is indexed in real-time
     * or through a bulk reindex operation.
     */
    @Inject
    PersonIndexingService personIndexingService;

    /** The PERSON table has no tenant: every person belongs to this one. */
    @ConfigProperty(name = "fuzzy-search.tenant-id", defaultValue = "default")
    String tenantId;

    /**
     * Creates a new person record.
     *
     * <p>Every column is protected as a whole with the transformer of the
     * rgd-http-proxy and the mapping of its column, exactly like the Flight SQL
     * {@code transform()} extension of the proxy does: a person created here is
     * readable through the proxy and the other way round.
     *
     * <p>The persistence unit is RESOURCE_LOCAL, so JTA {@code @Transactional}
     * has no effect. The {@link EntityTransaction} is managed explicitly:
     * begin → assign the next identifier → persist → commit. The {@code PERSON}
     * table has neither identity nor sequence, so the identifier is
     * {@code MAX(ID) + 1}: two concurrent creations may collide, in which case
     * one of them fails and is rolled back.
     *
     * <p>After the DB commit, the entity is forwarded to
     * {@link PersonIndexingService#indexPerson(PersonEntity)} which computes
     * the phonetic signatures and pushes the document to OpenSearch.
     */
    @POST
    public PersonResponse create(PersonCreateRequest req) {

        // 1. Protect every column in a single transformer call
        List<String> protectedValues = protectionService.protectAll(List.of(
                new ColumnValue(PersonEntity.FIRST_NAME, req.firstName()),
                new ColumnValue(PersonEntity.LAST_NAME, req.lastName()),
                new ColumnValue(PersonEntity.BIRTH_DATE, req.birthDate()),
                new ColumnValue(PersonEntity.EMAIL, req.email()),
                new ColumnValue(PersonEntity.CITY, req.city()),
                new ColumnValue(PersonEntity.NOTES, req.notes())));

        var entity = new PersonEntity();
        entity.firstName = protectedValues.get(0);
        entity.lastName  = protectedValues.get(1);
        entity.birthDate = protectedValues.get(2);
        entity.email     = protectedValues.get(3);
        entity.city      = protectedValues.get(4);
        entity.notes     = protectedValues.get(5);

        // 2. Persist with explicit RESOURCE_LOCAL transaction
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();
            Number maxId = em.createNamedQuery(PersonEntity.MAX_ID, Number.class).getSingleResult();
            entity.id = maxId == null ? 1 : maxId.intValue() + 1;
            em.persist(entity);
            tx.commit();
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw new RuntimeException("Failed to persist person: " + e.getMessage(), e);
        }

        // 3. Index in OpenSearch using the shared PersonIndexingService.
        //    This is the same code path used by AdminResource.reindex(), ensuring
        //    identical signatures whether the person was just created or re-indexed.
        personIndexingService.indexPerson(entity);

        // 4. Return the plain-text view to the caller
        return new PersonResponse(entity.id, req.firstName(), req.lastName(), tenantId,
                req.birthDate(), req.email(), req.city(), req.notes());
    }
}
