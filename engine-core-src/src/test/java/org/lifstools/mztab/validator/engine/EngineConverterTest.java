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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lifstools.mztab.validator.engine.api.ValidationMessage;
import org.lifstools.mztab2.cvmapping.CvParameterLookupService;
import org.lifstools.mztab2.model.MzTab;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Ported from the webapp's MzTabConverterTest. A body that cannot be read,
 * e.g. one over the upload limit, leaves no temporary file behind.
 */
class EngineConverterTest {

    @TempDir
    Path tmp;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EngineConverter converter = new EngineConverter(
        new EngineValidator(mock(CvParameterLookupService.class), objectMapper), objectMapper);

    @Test
    void receiveKeepsTheBody() throws Exception {
        Path file = EngineConverter.receive(new ByteArrayInputStream(new byte[1024]), tmp);
        assertThat(file).hasSize(1024);
    }

    @Test
    void receiveDeletesWhatItReadOfABodyThatFails() throws Exception {
        InputStream failing = new SequenceInputStream(new ByteArrayInputStream(new byte[8192]),
            new InputStream() {
                @Override
                public int read() {
                    throw new MaxUploadSizeExceededException(8192);
                }
            });
        assertThatThrownBy(() -> EngineConverter.receive(failing, tmp))
            .isInstanceOf(MaxUploadSizeExceededException.class);
        try (Stream<Path> left = Files.list(tmp)) {
            assertThat(left).isEmpty();
        }
    }

    @Test
    void readsAValidFileIntoItsModel() throws Exception {
        EngineConverter.Conversion<MzTab> conversion = converter.read(
            new ByteArrayInputStream(Fixtures.ofThisLine(Fixtures.LIPIDOMICS)));

        assertThat(conversion.succeeded()).as("errors: %s", conversion.errors()).isTrue();
        assertThat(conversion.value().getSmallMoleculeSummary()).isNotEmpty();
    }

    @Test
    void readReportsOnlyErrors() throws Exception {
        EngineConverter.Conversion<MzTab> conversion = converter.read(
            new ByteArrayInputStream("garbage".getBytes(StandardCharsets.UTF_8)));

        assertThat(conversion.succeeded()).isFalse();
        assertThat(conversion.value()).isNull();
        assertThat(conversion.errors()).isNotEmpty()
            .allSatisfy(m -> assertThat(m.getMessageType()).isEqualTo(ValidationMessage.MessageType.ERROR));
    }

    @Test
    void writesTheModelAsTabSeparatedMzTab() throws Exception {
        MzTab model = converter.read(new ByteArrayInputStream(Fixtures.ofThisLine(Fixtures.LIPIDOMICS))).value();

        EngineConverter.Conversion<String> conversion = converter.write(
            new ByteArrayInputStream(objectMapper.writeValueAsBytes(model)), true);

        assertThat(conversion.succeeded()).as("errors: %s", conversion.errors()).isTrue();
        assertThat(conversion.value()).startsWith("MTD\tmzTab-version").contains("SMH\tSML_ID");
    }

    @Test
    void writeOfAModelWithoutMetadataIsAnError() throws Exception {
        EngineConverter.Conversion<String> conversion = converter.write(
            new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)), false);

        assertThat(conversion.succeeded()).isFalse();
        assertThat(conversion.errors()).singleElement()
            .satisfies(m -> assertThat(m.getMessage()).startsWith(EngineConverter.NOT_WRITTEN));
    }

    @Test
    void writeRejectsNonJson() {
        assertThatThrownBy(() -> converter.write(
            new ByteArrayInputStream("not json".getBytes(StandardCharsets.UTF_8)), true))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith(EngineConverter.NOT_JSON);
    }
}
