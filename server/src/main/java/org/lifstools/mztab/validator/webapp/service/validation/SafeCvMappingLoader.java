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
package org.lifstools.mztab.validator.webapp.service.validation;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Checks CV mapping files with a hardened XML parser before they are stored
 * for a validation. Mapping files may be uploaded by users: a DOCTYPE
 * declaration or an external entity is rejected, and the document must be
 * valid against the CvMapping XSD. The engines parse the mapping themselves,
 * with the same hardening; the webapp only decides whether to accept it.
 *
 * <p>The XSD, {@value #XSD}, is a copy of the one in jmzTab-m, which the webapp
 * does not depend on; the engines' {@code WebappCopiesTest} keeps the copy
 * equal to the XSD of their jmzTab-m.
 */
public final class SafeCvMappingLoader {

    static final String XSD = "/schema/CvMapping.xsd";
    private static volatile Schema schema;

    private SafeCvMappingLoader() {
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
}
