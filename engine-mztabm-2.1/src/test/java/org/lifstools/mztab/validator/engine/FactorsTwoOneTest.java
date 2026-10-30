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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lifstools.mztab.validator.engine.api.ValidationLevel;
import org.lifstools.mztab.validator.engine.api.ValidationMessage;
import org.lifstools.mztab2.cvmapping.CvParameterLookupService;
import org.lifstools.mztab2.model.MzTab;

/** mzTab-M 2.1 replaced study_variable[n]-factors by study_variable_group; the 2.1 engine says so and drops them. */
class FactorsTwoOneTest {

    @TempDir
    Path dir;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EngineValidator validator = new EngineValidator(mock(CvParameterLookupService.class), objectMapper);
    private final EngineConverter converter = new EngineConverter(validator, objectMapper);

    @Test
    void reportsWarn2067ForFactorsIn21File() throws IOException {
        Path file = dir.resolve("factors.mztab");
        try (InputStream in = getClass().getResourceAsStream("/study-variable-factors-2.1.mztab")) {
            Files.write(file, in.readAllBytes());
        }

        List<ValidationMessage> messages = validator.validate(file, ValidationLevel.INFO, 100, null).messages();

        assertThat(messages).anySatisfy(m -> {
            assertThat(m.getCode()).contains("2067");
            assertThat(m.getMessageType()).isEqualTo(ValidationMessage.MessageType.WARN);
        });
    }

    @Test
    void dropsFactorsThroughJsonRoundTrip() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/study-variable-factors-2.1.mztab")) {
            EngineConverter.Conversion<MzTab> read = converter.read(in);
            assertThat(read.succeeded()).as("errors: %s", read.errors()).isTrue();

            EngineConverter.Conversion<String> written = converter.write(
                new ByteArrayInputStream(objectMapper.writeValueAsBytes(read.value())), true);

            assertThat(written.succeeded()).as("errors: %s", written.errors()).isTrue();
            assertThat(written.value()).doesNotContain("study_variable[1]-factors");
        }
    }
}
