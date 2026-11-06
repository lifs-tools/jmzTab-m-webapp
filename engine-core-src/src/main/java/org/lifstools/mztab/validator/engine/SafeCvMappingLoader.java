/*
 * Copyright 2026 Leibniz Institut für Analytische Wissenschaften - ISAS e.V..
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lifstools.mztab.validator.engine;

import info.psidev.cvmapping.CvMapping;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.sax.SAXSource;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

/**
 * Loads CV mapping files with a hardened XML parser.
 *
 * jmzTab-m's {@code CvMappingValidator.of(File, ...)} unmarshals with a
 * default JAXB unmarshaller, which resolves DOCTYPE declarations and
 * entities. Mapping files may be uploaded by users, so the webapp parses them
 * itself and hands jmzTab-m the resulting {@link CvMapping} via
 * {@code CvMappingValidator.of(CvMapping, ...)}.
 */
public final class SafeCvMappingLoader {

    private static final JAXBContext CONTEXT = createContext();
    private static final String XSD = "/schema/CvMapping.xsd";
    private static volatile Schema schema;

    private SafeCvMappingLoader() {
    }

    public static CvMapping load(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return load(in);
        }
    }

    public static CvMapping load(InputStream in) {
        try {
            SAXSource source = new SAXSource(hardenedReader(), new InputSource(in));
            return (CvMapping) CONTEXT.createUnmarshaller().unmarshal(source);
        } catch (JAXBException e) {
            throw new InvalidMappingFileException("Mapping file rejected: " + describe(e), e);
        } catch (SAXException | ParserConfigurationException e) {
            throw new IllegalStateException("Could not configure a secure XML parser", e);
        }
    }

    /**
     * Checks a mapping file against the CvMapping XSD, after the hardened SAX
     * pass has rejected DOCTYPEs and external entities.
     *
     * @throws InvalidMappingFileException if the document is not well-formed or not schema-valid
     */
    public static void validateAgainstSchema(byte[] xml) {
        try {
            XMLReader reader = hardenedReader();
            reader.setContentHandler(new DefaultHandler());
            reader.setErrorHandler(new DefaultHandler() {
                @Override
                public void fatalError(SAXParseException e) throws SAXException {
                    throw e;
                }
            });
            reader.parse(new InputSource(new ByteArrayInputStream(xml)));

            Validator validator = schema().newValidator();
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            validator.validate(new StreamSource(new ByteArrayInputStream(xml)));
        } catch (SAXException | IOException e) {
            throw new InvalidMappingFileException("Mapping file rejected: " + e.getMessage(), e);
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("Could not configure a secure XML parser", e);
        }
    }

    /** The compiled XSD; {@link Schema} is immutable and thread-safe, so it is compiled once. */
    private static Schema schema() {
        Schema s = schema;
        if (s == null) {
            synchronized (SafeCvMappingLoader.class) {
                s = schema;
                if (s == null) {
                    s = compileSchema();
                    schema = s;
                }
            }
        }
        return s;
    }

    private static Schema compileSchema() {
        try (InputStream xsd = SafeCvMappingLoader.class.getResourceAsStream(XSD)) {
            if (xsd == null) {
                throw new IllegalStateException(XSD + " is missing from the classpath");
            }
            SchemaFactory schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            schemaFactory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            schemaFactory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return schemaFactory.newSchema(new StreamSource(xsd));
        } catch (SAXException | IOException e) {
            throw new IllegalStateException("Could not compile " + XSD, e);
        }
    }

    private static XMLReader hardenedReader() throws SAXException, ParserConfigurationException {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        return factory.newSAXParser().getXMLReader();
    }

    /** The parser's own message, e.g. "DOCTYPE is disallowed ...", without the stack of wrappers. */
    private static String describe(JAXBException e) {
        Throwable cause = e.getLinkedException() != null ? e.getLinkedException() : e.getCause();
        String message = cause != null ? cause.getMessage() : e.getMessage();
        return message != null ? message : e.toString();
    }

    private static JAXBContext createContext() {
        try {
            return JAXBContext.newInstance(CvMapping.class);
        } catch (JAXBException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
