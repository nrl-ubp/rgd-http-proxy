package com.ubp.rgd.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SequenceWriter;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.javafaker.Faker;

import java.io.BufferedReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.javafaker.Name;
import com.ubp.rgd.proxy.utils.CliArgs;

/**
 * Utility program to generate sample random client data. See program switches to accomodate for your needs.
 */
public class RandomPersonGenerator {

    private static final Logger LOG = LoggerFactory.getLogger(RandomPersonGenerator.class);

    public static void main(String[] args) {

        LOG.info("Random person generator starting...");

        CliArgs cliArgs = new CliArgs(args);

        long rows = cliArgs.switchLongValue("--rows", 10000L);
        String fileFormat = cliArgs.switchValue("--file-format", "JSON");
        String fileName = cliArgs.switchValue("--filename", String.format("%d_random_person_data.%s", rows, fileFormat.toLowerCase()));
        String locale = cliArgs.switchValue("--locale", "de-CH");
        String dateFormat = cliArgs.switchValue("--date-format", "yyyy-MM-dd");

        LOG.info("Generation parameters:");
        LOG.info("--rows       : {} (default 10k)", rows);
        LOG.info("--filename   : {} (default [rows]_random_person_data.csv)", fileName);
        LOG.info("--file-format: {} (CSV or JSON by default)", fileFormat);
        LOG.info("--locale     : {} (default ch-DE)", locale);
        LOG.info("--date-format: {} (default dd.MM.yyyy)", dateFormat);

        if (!fileFormat.equalsIgnoreCase("CSV") && !fileFormat.equalsIgnoreCase("JSON")) {
            LOG.error("Invalid --file-format value. Expected CSV or JSON: {}", fileFormat);
            System.exit(-1);
        }

        Faker faker = new Faker(Locale.forLanguageTag(locale));
        DateFormat dtFormat = new SimpleDateFormat(dateFormat);

        long startTime = System.currentTimeMillis();
        LOG.info("Starting random generation...");

        String[] columnNames = {
            "AccountId",
            "CRId",
            "LongName",
            "ShortName",
            "ReportingCurrency",
            "Status",
            "OpenDate",
            "CloseDate",
            "ManagementType",
            "RelationshipManagerId",
            "BienTrouve",
            "AccountOriginId",
            "BienTrouveLastDate",
            "DormantStatus",
            "LastDateOfContact",
            "LastDateOfContactReason",
            "StatusDate",
            "AccountApprovalDate",
            "SSD"
        };

        Random random = new Random();

        StringBuffer sb = new StringBuffer();
        sb.append(String.join(",", columnNames)).append("\n");

        for (int i = 0; i < rows; i++) {

            int compNum = 101 + random.nextInt(899);
            int accNum = random.nextInt(9999999);

            String accountId = String.format("%d.%07d", compNum, accNum);
            String CRId = String.format("%s.%07d", faker.country().countryCode2().toUpperCase(), accNum);
            Name name = faker.name();
            String firstName = name.firstName();
            String lastName = name.lastName();
            String mr = name.prefix();
            String longName = String.format("%s %s %s", mr, firstName, lastName);
            String shortName = String.format("%s", lastName);
            String reportingCurrency = faker.currency().code();
            String status = random.nextBoolean() ? "Active" : "Inactive";
            Date openDt = faker.date().birthday(18, 120);
            String openDate = dtFormat.format(openDt);
            String closeDate = "Inactive".equalsIgnoreCase(status) ? dtFormat.format(faker.date().between(openDt, new Date())) : "";
            String managementType = random.nextBoolean() ? "Advisory" : "Discretionary";
            String relationshipManagerId = faker.code().isbn10();
            String bienTrouve = random.nextBoolean() ? "true" : "false";
            String accountOriginId = String.format("ID%05d", random.nextInt(99999));
            String bienTrouveLastDate = dtFormat.format(faker.date().between(openDt, new Date()));
            String dormantStatus = "DOA_2";
            String lastDateOfContact = dtFormat.format(faker.date().between(openDt, new Date()));
            String lastDateOfContactReason = "LDO_3";
            String statusDate = dtFormat.format(faker.date().between(openDt, new Date()));
            String accountApprovalDate = dtFormat.format(faker.date().between(openDt, new Date()));
            String sdd = random.nextBoolean() ? "true" : "false";

            sb.append(String.format("%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s\n",
                    accountId, CRId, longName, shortName, reportingCurrency, status,
                    openDate, closeDate, managementType, relationshipManagerId,
                    bienTrouve, accountOriginId, bienTrouveLastDate, dormantStatus,
                    lastDateOfContact, lastDateOfContactReason, statusDate, accountApprovalDate, sdd));
        }

        if ("CSV".equalsIgnoreCase(fileFormat)) {
            try (FileWriter writer = new FileWriter(fileName, StandardCharsets.UTF_8)) {
                writer.append(String.join(",", columnNames)).append("\n");
                writer.append(sb);
            } catch (IOException e) {
                LOG.error("Cannot generate random data.", e);
                System.exit(-1);
            }
        }

        if ("JSON".equalsIgnoreCase(fileFormat)) {
            try (StringReader sr = new StringReader(sb.toString());
                 BufferedReader br = new BufferedReader(sr)) {
                String line;
                ObjectMapper mapper = new ObjectMapper();
                List<ObjectNode> objectList = new ArrayList<>();
                boolean firstLine = true;
                while ((line = br.readLine()) != null) {
                    if (firstLine) {
                        firstLine = false;
                        continue;
                    }
                    String[] values = line.split(",");
                    ObjectNode jsonObject = mapper.createObjectNode();
                    for (int col = 0; col < values.length; col++) {
                        jsonObject.put(columnNames[col], values[col]);
                    }
                    objectList.add(jsonObject);
                }

                try (FileWriter writer = new FileWriter(fileName, StandardCharsets.UTF_8);
                     SequenceWriter sw = mapper.writerWithDefaultPrettyPrinter().writeValuesAsArray(writer)) {
                    sw.writeAll(objectList);
                }
            } catch (IOException ioe) {
                LOG.error("Cannot generate JSON object list from CSV contents.", ioe);
                System.exit(-2);
            }
        }

        long endTime = System.currentTimeMillis();

        float durationTime = (endTime-startTime) / 1000f;

        LOG.info(String.format("Data generation completed in %.2f seconds. Check the file: %s.", durationTime, fileName));
        System.exit(0);
    }
}

