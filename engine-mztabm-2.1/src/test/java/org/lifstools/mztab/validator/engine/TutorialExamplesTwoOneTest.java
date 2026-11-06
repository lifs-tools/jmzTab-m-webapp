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
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.lifstools.mztab.validator.engine.api.ValidationLevel;
import org.lifstools.mztab.validator.engine.api.ValidationMessage;
import org.lifstools.mztab2.cvmapping.CvParameterLookupService;

/**
 * The bundled mzTab-M 2.1 teaching and study design files must never teach something the validator rejects: they
 * parse on the 2.1 engine without errors or warnings. The files live in the server's resources, which bundle them.
 */
class TutorialExamplesTwoOneTest {

    private final EngineValidator validator = new EngineValidator(mock(CvParameterLookupService.class),
        new ObjectMapper());

    @ParameterizedTest
    @ValueSource(strings = {"teaching-lipidomics-2.1.mztab", "study-design-2.1.mztab"})
    void validatesWithoutErrorsOrWarnings(String name) throws IOException {
        // surefire runs in target/surefire-files; webapp.dir is the server module, set by the engine build
        String webapp = System.getProperty("webapp.dir");
        assertThat(webapp).as("webapp.dir is set by the engine build").isNotNull();
        Path file = Path.of(webapp, "src", "main", "resources", "tutorial", name);

        EngineValidator.Outcome outcome = validator.validate(file, ValidationLevel.INFO, 500, null);

        List<String> messages = outcome.messages().stream()
            .map(m -> m.getMessageType() + " " + m.getCode() + " line " + m.getLineNumber() + ": " + m.getMessage())
            .toList();
        assertThat(outcome.mzTab()).as("parsed; messages: %s", messages).isNotNull();
        assertThat(outcome.messages()).as("messages: %s", messages)
            .noneMatch(m -> m.getMessageType() == ValidationMessage.MessageType.ERROR
                || m.getMessageType() == ValidationMessage.MessageType.WARN);
    }
}
