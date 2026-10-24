package org.lifstools.mztab.validator.webapp.service.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lifstools.mztab.validator.webapp.domain.ValidationLevel;
import org.lifstools.mztab2.cvmapping.CvParameterLookupService;
import org.lifstools.mztab2.model.MzTab;
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

    @Test
    void semanticMessagesAreCappedAtMaxErrorsWithOneOmissionNote() throws IOException {
        MzTabMValidator stubbed = semanticResult(30);

        List<ValidationMessage> messages = stubbed.validateAndParse(mzTabFile, ValidationLevel.INFO, 10,
            dir.resolve("mapping.xml")).messages();

        assertEquals(10, messages.stream().filter(m -> m.getMessage().startsWith("semantic ")).count());
        assertEquals("semantic 9", messages.get(messages.size() - 2).getMessage());
        ValidationMessage note = messages.get(messages.size() - 1);
        assertEquals("20 further semantic validation messages were omitted. "
            + "Raise the maximum number of messages to see them.", note.getMessage());
        assertEquals(ValidationMessage.MessageTypeEnum.INFO, note.getMessageType());
        assertEquals(ValidationMessage.CategoryEnum.CROSS_CHECK, note.getCategory());
        assertEquals("", note.getCode());
        assertEquals(-1L, note.getLineNumber());
    }

    @Test
    void semanticMessagesUpToMaxErrorsAreKeptUnchanged() throws IOException {
        MzTabMValidator stubbed = semanticResult(10);
        List<ValidationMessage> structural = validator.validateAndParse(mzTabFile, ValidationLevel.INFO, 10, null)
            .messages();

        List<ValidationMessage> messages = stubbed.validateAndParse(mzTabFile, ValidationLevel.INFO, 10,
            dir.resolve("mapping.xml")).messages();

        assertEquals(structural.size() + 10, messages.size());
        assertEquals("semantic 9", messages.get(messages.size() - 1).getMessage());
        assertFalse(messages.stream().anyMatch(m -> m.getMessage().contains("omitted")));
    }

    @Test
    void semanticValidationIsSkippedWithANoteWhenTheFileHasNoModel() throws IOException {
        // a data line without its header line: the parser stops and builds no model
        Path broken = Files.writeString(dir.resolve("broken.mztab"),
            Files.readString(mzTabFile) + "SML\t1\n");
        MzTabMValidator stubbed = semanticResult(3);

        MzTabMValidator.Outcome outcome = stubbed.validateAndParse(broken, ValidationLevel.INFO, 10,
            dir.resolve("mapping.xml"));

        assertNull(outcome.mzTab());
        assertFalse(outcome.messages().stream().anyMatch(m -> m.getMessage().startsWith("semantic ")),
            "semantic validation must not run: " + outcome.messages());
        assertFalse(outcome.messages().stream().anyMatch(m -> m.getMessage().contains("Semantic validation failed")));
        ValidationMessage note = outcome.messages().get(outcome.messages().size() - 1);
        assertEquals("Semantic validation was skipped: the errors above prevent reading the file's content. "
            + "Fix them and validate again.", note.getMessage());
        assertEquals(ValidationMessage.MessageTypeEnum.INFO, note.getMessageType());
        assertEquals(ValidationMessage.CategoryEnum.CROSS_CHECK, note.getCategory());
        assertEquals("", note.getCode());
        assertEquals(-1L, note.getLineNumber());
    }

    /** A validator whose semantic validation returns {@code n} messages "semantic 0" … */
    private MzTabMValidator semanticResult(int n) {
        return new MzTabMValidator(lookupService) {
            @Override
            List<ValidationMessage> validateSemantics(Path file, MzTab mzTab, Path mapping) {
                return IntStream.range(0, n)
                    .mapToObj(i -> new ValidationMessage().message("semantic " + i)
                        .messageType(ValidationMessage.MessageTypeEnum.WARN)
                        .category(ValidationMessage.CategoryEnum.CROSS_CHECK))
                    .toList();
            }
        };
    }

    private Path copyResource(String resource, String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            Path target = dir.resolve(name);
            Files.copy(in, target);
            return target;
        }
    }
}
