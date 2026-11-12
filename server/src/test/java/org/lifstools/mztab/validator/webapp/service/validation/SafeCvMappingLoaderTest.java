package org.lifstools.mztab.validator.webapp.service.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** The webapp checks a mapping file before it accepts it; the engines parse it. */
class SafeCvMappingLoaderTest {

    @Test
    void acceptsTheDefaultProfileOfTheCatalog() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/validation-profiles/mztab-m-2.0-default/1.0.0.xml")) {
            byte[] xml = in.readAllBytes();
            assertDoesNotThrow(() -> SafeCvMappingLoader.validateAgainstSchema(xml));
        }
    }

    @Test
    void rejectsAnyDoctypeDeclaration() {
        InvalidMappingFileException ex = assertThrows(InvalidMappingFileException.class,
            () -> SafeCvMappingLoader.validateAgainstSchema(bytes(MappingFixtures.MAPPING_WITH_DOCTYPE)));
        assertTrue(ex.getMessage().contains("DOCTYPE"), ex.getMessage());
    }

    @Test
    void rejectsMalformedXml() {
        assertThrows(InvalidMappingFileException.class,
            () -> SafeCvMappingLoader.validateAgainstSchema(bytes("<CvMapping><unclosed></CvMapping>")));
    }

    @Test
    void rejectsADocumentThatIsNotACvMapping() {
        assertThrows(InvalidMappingFileException.class,
            () -> SafeCvMappingLoader.validateAgainstSchema(bytes("<Mapping/>")));
    }

    /** The XSD is the webapp's own copy of jmzTab-m's; the webapp has no jmzTab-m. */
    @Test
    void theXsdIsOnTheClasspath() {
        assertTrue(SafeCvMappingLoader.class.getResource(SafeCvMappingLoader.XSD) != null);
    }

    private static byte[] bytes(String xml) {
        return xml.getBytes(StandardCharsets.UTF_8);
    }
}
