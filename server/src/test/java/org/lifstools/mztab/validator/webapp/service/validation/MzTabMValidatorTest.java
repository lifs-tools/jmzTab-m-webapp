package org.lifstools.mztab.validator.webapp.service.validation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lifstools.mztab2.cvmapping.CvParameterLookupService;
import org.lifstools.mztab2.model.ValidationMessage;

class MzTabMValidatorTest {

    @TempDir
    Path dir;

    private Path mzTabFile;
    private CvParameterLookupService lookupService;
    private MzTabMValidator validator;

    @BeforeEach
    void setUp() throws IOException {
        mzTabFile = copyResource("/metabolomics/minimal-m-2.0.mztab", "minimal.mztab");
        lookupService = mock(CvParameterLookupService.class);
        validator = new MzTabMValidator(lookupService);
    }

    @Test
    void mappingFileWithDoctypeIsRejectedBeforeSemanticValidation() throws IOException {
        Path mapping = Files.writeString(dir.resolve("mapping.xml"), MappingFixtures.MAPPING_WITH_DOCTYPE);

        List<ValidationMessage> messages = validator.validate(mzTabFile, "info", 100, true, mapping);

        assertTrue(messages.stream().anyMatch(m -> m.getMessage().contains("Mapping file rejected")),
            "expected a rejection message, got: " + messages);
        verifyNoInteractions(lookupService);
    }

    @Test
    void bundledDefaultMappingIsAccepted() throws IOException {
        Path mapping = copyResource("/mappings/mzTab-M-mapping.xml", "default-mapping.xml");

        List<ValidationMessage> messages = validator.validate(mzTabFile, "info", 100, true, mapping);

        assertFalse(messages.stream().anyMatch(m -> m.getMessage().contains("Mapping file rejected")),
            "default mapping must load, got: " + messages);
    }

    @Test
    void mappingFileIsNotReadWithoutSemanticValidation() throws IOException {
        Path mapping = Files.writeString(dir.resolve("mapping.xml"), MappingFixtures.MAPPING_WITH_DOCTYPE);

        List<ValidationMessage> messages = validator.validate(mzTabFile, "info", 100, false, mapping);

        assertFalse(messages.stream().anyMatch(m -> m.getMessage().contains("Mapping file rejected")));
    }

    private Path copyResource(String resource, String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            Path target = dir.resolve(name);
            Files.copy(in, target);
            return target;
        }
    }
}
