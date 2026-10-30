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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;
import org.lifstools.mztab2.cvmapping.CvParameterLookupService;
import org.lifstools.mztab2.model.MzTab;
import static org.mockito.Mockito.mock;

/** mzTab-M 2.0 describes factors with study_variable[n]-factors; the 2.0 engine keeps them. */
class FactorsTwoZeroTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EngineConverter converter = new EngineConverter(
        new EngineValidator(mock(CvParameterLookupService.class), objectMapper), objectMapper);

    @Test
    void keepsFactorsThroughJsonRoundTrip() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/study-variable-factors-2.0.mztab")) {
            EngineConverter.Conversion<MzTab> read = converter.read(in);
            assertThat(read.succeeded()).as("errors: %s", read.errors()).isTrue();

            EngineConverter.Conversion<String> written = converter.write(
                new ByteArrayInputStream(objectMapper.writeValueAsBytes(read.value())), true);

            assertThat(written.succeeded()).as("errors: %s", written.errors()).isTrue();
            assertThat(written.value()).contains("study_variable[1]-factors");
        }
    }
}
