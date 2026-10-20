package com.ubp.rgd.proxy.services;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Objects;

public class Person {
    @JsonProperty(value = "firstName")
    public String firstName;

    @JsonProperty(value = "lastName")
    public String lastName;

    @JsonProperty(value = "birthDate")
    public Date birthDate;

    @JsonProperty(value = "phoneNumber")
    public String phoneNumber;

    @JsonProperty(value = "email")
    public String email;

    @JsonProperty(value = "accountNumber")
    public String iban;

    public static final DateFormat dfBirthDate = new SimpleDateFormat("dd.MM.yyyy");

    public Person() {

    }

    public Person(String firstName, String lastName, String birthDateStr, String phoneNumber, String email, String iban) throws ParseException {
        this(firstName, lastName, dfBirthDate.parse(birthDateStr), phoneNumber, email, iban);
    }

    public Person(String firstName, String lastName, Date birthDate, String phoneNumber, String email, String iban) {
        this.firstName = firstName;
        this.lastName = lastName;
        this.birthDate = birthDate;
        this.phoneNumber = phoneNumber;
        this.email = email;
        this.iban = iban;
    }

    @Override
    public String toString() {
        return String.format("%s,%s,%s,%s,%s,%s",
                firstName, lastName, birthDate == null ? "NULL" : dfBirthDate.format(birthDate), phoneNumber, email, iban);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Person person = (Person) o;
        return Objects.equals(firstName, person.firstName) && Objects.equals(lastName, person.lastName) && Objects.equals(birthDate, person.birthDate) && Objects.equals(phoneNumber, person.phoneNumber) && Objects.equals(email, person.email) && Objects.equals(iban, person.iban);
    }

    @Override
    public int hashCode() {
        return Objects.hash(firstName, lastName, birthDate, phoneNumber, email, iban);
    }

    /**
     * Loads the persons.json file and return the contents as a byte array
     * @return contents of the persons.json file
     * @throws IOException in case of file not found or any error while reading the file.
     */
    public static byte[] getPersonsFileContents() throws IOException {
        URL resource = Thread.currentThread().getContextClassLoader().getResource("person.json");
        if (resource == null) {
            throw new FileNotFoundException("Resource file File not found!");
        }
        Path path;
        try {
            path = Paths.get(resource.toURI());
        } catch (URISyntaxException e) {
            throw new IOException(e);
        }
        if (Files.exists(path)) {
            return Files.readAllBytes(path);
        } else {
            throw new IOException("File not found: person.json");
        }
    }

}
