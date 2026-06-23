package com.ubp.rgd.proxy.utils;


import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.Unmarshaller;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;

public class XMLFile {

    public static String serialize(Object obj) throws IOException {
        try {
            JAXBContext jc = JAXBContext.newInstance(obj.getClass());

            StringWriter sw = new StringWriter();

            Marshaller marshaller = jc.createMarshaller();
            marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
            marshaller.marshal(obj, sw);

            return sw.toString();
        } catch (Exception ex) {
            throw new IOException(ex);
        }
    }

    public static void saveAs(File file, Object obj) throws IOException {
        try {
            JAXBContext jc = JAXBContext.newInstance(obj.getClass());
            Marshaller marshaller = jc.createMarshaller();
            marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
            marshaller.marshal(obj, file);
        } catch (Exception ex) {
            throw new IOException(ex);
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> T load(InputStream in, Class<T> clazz) throws IOException {
        try (InputStream source = in) {
            JAXBContext jc = JAXBContext.newInstance(clazz);
            Unmarshaller unmarshaller = jc.createUnmarshaller();
            return (T) unmarshaller.unmarshal(source);
        } catch (Exception ex) {
            throw new IOException(ex);
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> T load(File file, Class<T> clazz) throws IOException {
        try {
            JAXBContext jc = JAXBContext.newInstance(clazz);
            Unmarshaller unmarshaller = jc.createUnmarshaller();
            return (T) unmarshaller.unmarshal(file);
        } catch (Exception ex) {
            throw new IOException(ex);
        }
    }
}
