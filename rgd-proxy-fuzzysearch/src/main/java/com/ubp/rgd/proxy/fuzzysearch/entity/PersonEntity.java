package com.ubp.rgd.proxy.fuzzysearch.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.Table;

import java.util.List;

/**
 * A row of the {@code PERSON} table, as loaded by the rgd-http-proxy {@code PersonFlightSqlLoader}.
 * <p>
 * Every column but the identifier holds a value protected by the transformer of the proxy, with the
 * RPS class and property its column is mapped to. The birth date is text, as an FPE protected date
 * stays a date in the same format but an RPS one becomes an {@code RG{...}} token.
 */
@Entity
@Table(name = PersonEntity.TABLE)
@NamedQuery(name = PersonEntity.SEARCH,
        query = "SELECT p.id FROM PersonEntity p WHERE p.firstName IN :firstNames OR p.lastName IN :lastNames")
@NamedQuery(name = PersonEntity.SELECT_ALL, query = "SELECT p FROM PersonEntity p ORDER BY p.id")
@NamedQuery(name = PersonEntity.SELECT_IN_IDS, query = "SELECT p FROM PersonEntity p WHERE p.id IN :ids")
@NamedQuery(name = PersonEntity.MAX_ID, query = "SELECT MAX(p.id) FROM PersonEntity p")
public class PersonEntity {

    public static final String TABLE = "PERSON";

    public static final String FIRST_NAME = "FIRST_NAME";
    public static final String LAST_NAME = "LAST_NAME";
    public static final String BIRTH_DATE = "BIRTH_DATE";
    public static final String EMAIL = "EMAIL";
    public static final String CITY = "CITY";
    public static final String NOTES = "NOTES";

    /** The columns holding protected values, each of which needs a column mapping. */
    public static final List<String> PROTECTED_COLUMNS = List.of(FIRST_NAME, LAST_NAME, BIRTH_DATE, EMAIL, CITY, NOTES);

    public static final String SELECT_IN_IDS = "PersonEntity.selectInIds";
    public static final String SELECT_ALL = "PersonEntity.selectAll";
    public static final String SEARCH = "PersonEntity.search";
    public static final String MAX_ID = "PersonEntity.maxId";

    /** Assigned by the application: the table has neither identity nor sequence. */
    @Id
    @Column(name = "ID")
    public Integer id;

    @Column(name = FIRST_NAME)
    public String firstName;

    @Column(name = LAST_NAME)
    public String lastName;

    @Column(name = BIRTH_DATE)
    public String birthDate;

    @Column(name = EMAIL)
    public String email;

    @Column(name = CITY)
    public String city;

    @Column(name = NOTES)
    public String notes;
}
