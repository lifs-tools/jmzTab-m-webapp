/*
 * Copyright 2026 LIFS Tools Consortium.
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lifstools.mztab.validator.engine.api.ValidationLevel;
import org.lifstools.mztab.validator.engine.api.ValidationMessage;
import org.lifstools.mztab2.cvmapping.CvParameterLookupService;
import org.lifstools.mztab2.model.MzTab;

/** Ported from the webapp's MzTabMValidatorTest; messages are the engine-api DTOs. */
class EngineValidatorTest {

    @TempDir
    Path dir;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private Path mzTabFile;
    private CvParameterLookupService lookupService;
    private EngineValidator validator;

    @BeforeEach
    void setUp() throws IOException {
        mzTabFile = Files.write(dir.resolve("minimal.mztab"), Fixtures.ofThisLine(Fixtures.MINIMAL));
        lookupService = mock(CvParameterLookupService.class);
        validator = new EngineValidator(lookupService, objectMapper);
    }

    @Test
    void parsesAValidFile() throws IOException {
        EngineValidator.Outcome outcome = validator.validate(mzTabFile, ValidationLevel.INFO, 100, null);

        assertNotNull(outcome.mzTab());
        assertFalse(outcome.messages().stream()
            .anyMatch(m -> m.getMessageType() == ValidationMessage.MessageType.ERROR), outcome.messages().toString());
    }

    @Test
    void convertsParserMessagesWithCodeLineAndCategory() throws IOException {
        Path broken = Files.writeString(dir.resolve("no-version.mztab"),
            new String(Fixtures.withoutVersion(Fixtures.resource(Fixtures.MINIMAL))));

        List<ValidationMessage> messages = validator.validate(broken, ValidationLevel.INFO, 100, null).messages();

        ValidationMessage missing = messages.stream().filter(m -> m.getCode().contains("2008")).findFirst()
            .orElseThrow(() -> new AssertionError("expected Error-2008, got " + messages));
        assertEquals(ValidationMessage.MessageType.ERROR, missing.getMessageType());
        assertNotNull(missing.getCategory());
        assertNotNull(missing.getLineNumber());
    }

    @Test
    void unreadableFileIsReportedNotThrown() throws IOException {
        EngineValidator.Outcome outcome = validator.validate(dir.resolve("missing.mztab"), ValidationLevel.INFO,
            100, null);

        assertNull(outcome.mzTab());
        assertFalse(outcome.messages().isEmpty());
        assertEquals(ValidationMessage.MessageType.ERROR, outcome.messages().get(0).getMessageType());
    }

    @Test
    void mappingFileWithDoctypeIsRejectedBeforeSemanticValidation() throws IOException {
        Path mapping = Files.writeString(dir.resolve("mapping.xml"), MappingFixtures.MAPPING_WITH_DOCTYPE);

        List<ValidationMessage> messages = validator.validate(mzTabFile, ValidationLevel.INFO, 100, mapping)
            .messages();

        assertTrue(messages.stream().anyMatch(m -> m.getMessage().contains("Mapping file rejected")),
            "expected a rejection message, got: " + messages);
        verifyNoInteractions(lookupService);
    }

    @Test
    void bundledDefaultMappingIsAccepted() throws IOException {
        Path mapping = Files.write(dir.resolve("default-mapping.xml"), Fixtures.resource(Fixtures.BUNDLED_MAPPING));

        List<ValidationMessage> messages = validator.validate(mzTabFile, ValidationLevel.INFO, 100, mapping)
            .messages();

        assertFalse(messages.stream().anyMatch(m -> m.getMessage().contains("Mapping file rejected")),
            "default mapping must load, got: " + messages);
    }

    @Test
    void mappingFileIsNotReadWithoutSemanticValidation() throws IOException {
        List<ValidationMessage> messages = validator.validate(mzTabFile, ValidationLevel.INFO, 100, null).messages();

        assertFalse(messages.stream().anyMatch(m -> m.getMessage().contains("Mapping file rejected")));
        verifyNoInteractions(lookupService);
    }

    @Test
    void semanticMessagesAreCappedAtMaxErrorsWithOneOmissionNote() throws IOException {
        EngineValidator stubbed = semanticResult(30);

        List<ValidationMessage> messages = stubbed.validate(mzTabFile, ValidationLevel.INFO, 10,
            dir.resolve("mapping.xml")).messages();

        assertEquals(10, messages.stream().filter(m -> m.getMessage().startsWith("semantic ")).count());
        assertEquals("semantic 9", messages.get(messages.size() - 2).getMessage());
        ValidationMessage note = messages.get(messages.size() - 1);
        assertEquals("20 further semantic validation messages were omitted. "
            + "Raise the maximum number of messages to see them.", note.getMessage());
        assertEquals(ValidationMessage.MessageType.INFO, note.getMessageType());
        assertEquals(ValidationMessage.Category.CROSS_CHECK, note.getCategory());
        assertEquals("", note.getCode());
        assertEquals(-1L, note.getLineNumber());
    }

    @Test
    void semanticMessagesUpToMaxErrorsAreKeptUnchanged() throws IOException {
        EngineValidator stubbed = semanticResult(10);
        List<ValidationMessage> structural = validator.validate(mzTabFile, ValidationLevel.INFO, 10, null)
            .messages();

        List<ValidationMessage> messages = stubbed.validate(mzTabFile, ValidationLevel.INFO, 10,
            dir.resolve("mapping.xml")).messages();

        assertEquals(structural.size() + 10, messages.size());
        assertEquals("semantic 9", messages.get(messages.size() - 1).getMessage());
        assertEquals(ValidationMessage.MessageType.WARN, messages.get(messages.size() - 1).getMessageType());
        assertFalse(messages.stream().anyMatch(m -> m.getMessage().contains("omitted")));
    }

    @Test
    void semanticValidationIsSkippedWithANoteWhenTheFileHasNoModel() throws IOException {
        // a data line without its header line: the parser stops and builds no model
        Path broken = Files.writeString(dir.resolve("broken.mztab"),
            Files.readString(mzTabFile) + "SML\t1\n");
        EngineValidator stubbed = semanticResult(3);

        EngineValidator.Outcome outcome = stubbed.validate(broken, ValidationLevel.INFO, 10,
            dir.resolve("mapping.xml"));

        assertNull(outcome.mzTab());
        assertFalse(outcome.messages().stream().anyMatch(m -> m.getMessage().startsWith("semantic ")),
            "semantic validation must not run: " + outcome.messages());
        assertFalse(outcome.messages().stream().anyMatch(m -> m.getMessage().contains("Semantic validation failed")));
        ValidationMessage note = outcome.messages().get(outcome.messages().size() - 1);
        assertEquals("Semantic validation was skipped: the errors above prevent reading the file's content. "
            + "Fix them and validate again.", note.getMessage());
        assertEquals(ValidationMessage.MessageType.INFO, note.getMessageType());
        assertEquals(ValidationMessage.Category.CROSS_CHECK, note.getCategory());
        assertEquals("", note.getCode());
        assertEquals(-1L, note.getLineNumber());
    }

    /** A validator whose semantic validation returns {@code n} messages "semantic 0" … */
    private EngineValidator semanticResult(int n) {
        return new EngineValidator(lookupService, objectMapper) {
            @Override
            List<org.lifstools.mztab2.model.ValidationMessage> validateSemantics(Path file, MzTab mzTab,
                Path mapping) {
                return IntStream.range(0, n)
                    .mapToObj(i -> new org.lifstools.mztab2.model.ValidationMessage().message("semantic " + i)
                        .messageType(org.lifstools.mztab2.model.ValidationMessage.MessageTypeEnum.WARN)
                        .category(org.lifstools.mztab2.model.ValidationMessage.CategoryEnum.CROSS_CHECK))
                    .toList();
            }
        };
    }
}
