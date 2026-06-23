package com.ubp.rgd.proxy.utils;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

public class JSONFile {

    private static final JsonMapper mapper;

    static {
        // serialize ONLY annotated properties
        mapper = JsonMapper.builder().disable(MapperFeature.AUTO_DETECT_CREATORS,
                MapperFeature.AUTO_DETECT_FIELDS,
                MapperFeature.AUTO_DETECT_GETTERS,
                MapperFeature.AUTO_DETECT_IS_GETTERS)
                .disable(SerializationFeature.WRITE_DATES_WITH_ZONE_ID)
                .enable(SerializationFeature.INDENT_OUTPUT)
                .build();
    }

    /**
     * Serialize to JSON string the object given as parameter, If it is a ResultSet then special handling
     * is made in this class. <strong>Please note that if passed a resultset object, then the resultset is NOT closed
     * by this method.</strong>
     */
    public static String serialize(Object obj) throws JsonProcessingException {
        try {
            return mapper.writeValueAsString(obj);
        } catch (Exception ex) {
            IOException ioe = new IOException("cannot parse Result set", ex);
            throw JsonMappingException.fromUnexpectedIOE(ioe);
        }
    }
    public static String serializeSpecial(Object obj) throws JsonProcessingException {
        try {
            return mapper.writeValueAsString(obj)
                    .replace("[ {","{")
                    .replace("}, {",",")
                    .replace("} ]","}")
                    .replace("[","]");
        } catch (Exception ex) {
            IOException ioe = new IOException("cannot parse Result set", ex);
            throw JsonMappingException.fromUnexpectedIOE(ioe);
        }
    }
    public static <T> T parse(String jsonStr, Class<T> clazz) throws IOException {
        try {
            return mapper.readValue(jsonStr.getBytes(), clazz);
        } catch (Exception ex) {
            throw new IOException(ex);
        }
    }

    public static <T> T load(InputStream in, Class<T> clazz) throws IOException {
        try {
            return mapper.readValue(in, clazz);
        } catch (Exception ex) {
            throw new IOException(ex);
        }
    }

    public static <T> T load(File file, Class<T> clazz) throws IOException {
        try {
            return mapper.readValue(file, clazz);
        } catch (Exception ex) {
            throw new IOException(ex);
        }
    }

    public static void saveAs(File file, Object obj) throws IOException {
        try {
            mapper.writeValue(file, obj);
        } catch (Exception ex) {
            throw new IOException(ex);
        }
    }
}