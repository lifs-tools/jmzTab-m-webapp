package org.lifstools.mztab.validator.webapp.service.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import info.psidev.cvmapping.CvMapping;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SafeCvMappingLoaderTest {

    @Test
    void loadsTheBundledDefaultMapping() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/mappings/mzTab-M-mapping.xml")) {
            CvMapping mapping = SafeCvMappingLoader.load(in);
            assertEquals(28, mapping.getCvMappingRuleList().getCvMappingRule().size());
        }
    }

    @Test
    void loadsAMappingFileFromDisk(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("mapping.xml"), MappingFixtures.MINIMAL_MAPPING);
        CvMapping mapping = SafeCvMappingLoader.load(file);
        assertEquals("test", mapping.getModelName());
    }

    @Test
    void rejectsAnyDoctypeDeclaration() {
        InvalidMappingFileException ex = assertThrows(InvalidMappingFileException.class,
            () -> SafeCvMappingLoader.load(stream(MappingFixtures.MAPPING_WITH_DOCTYPE)));
        assertTrue(ex.getMessage().contains("DOCTYPE"), ex.getMessage());
    }

    @Test
    void rejectsMalformedXml() {
        assertThrows(InvalidMappingFileException.class,
            () -> SafeCvMappingLoader.load(stream("<CvMapping><unclosed></CvMapping>")));
    }

    private static InputStream stream(String xml) {
        return new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
    }
}
