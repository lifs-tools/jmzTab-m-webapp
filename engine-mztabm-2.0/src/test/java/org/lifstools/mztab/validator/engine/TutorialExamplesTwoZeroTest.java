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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.lifstools.mztab.validator.engine.api.ValidationLevel;
import org.lifstools.mztab.validator.engine.api.ValidationMessage;
import org.lifstools.mztab.validator.engine.api.ValidationMessage.MessageType;
import org.lifstools.mztab2.cvmapping.CvParameterLookupService;

/**
 * The bundled mzTab-M 2.0 teaching file must never teach something the validator rejects: it parses on the 2.0
 * engine without errors or warnings. The file lives in the server's resources, which bundle it.
 */
class TutorialExamplesTwoZeroTest {

    private final EngineValidator validator = new EngineValidator(mock(CvParameterLookupService.class),
        new ObjectMapper());

    @ParameterizedTest
    @ValueSource(strings = {"teaching-lipidomics-2.0.mztab"})
    void validatesWithoutErrorsOrWarnings(String name) throws IOException {
        EngineValidator.Outcome outcome = validate(name);

        List<String> messages = text(outcome);
        assertThat(outcome.mzTab()).as("parsed; messages: %s", messages).isNotNull();
        assertThat(outcome.messages()).as("messages: %s", messages)
            .noneMatch(m -> m.getMessageType() == ValidationMessage.MessageType.ERROR
                || m.getMessageType() == ValidationMessage.MessageType.WARN);
    }

    /** The last step of the 2.0 tour says that, at level Info, the file gets Info-2052: it declares no profile. */
    @Test
    void reportsTheMissingProfileAsTheTourSays() throws IOException {
        JsonNode steps = new ObjectMapper().readTree(tutorial("teaching-lipidomics-2.0.tour.json").toFile())
            .get("steps");
        assertThat(steps.get(steps.size() - 1).get("text").asText()).contains("(Info-2052)");

        EngineValidator.Outcome outcome = validate("teaching-lipidomics-2.0.mztab");

        assertThat(outcome.messages()).as("messages: %s", text(outcome)).anySatisfy(m -> {
            // the profile inferred from the sections present: all four
            String message = "No mzTab-profile was declared in the metadata section. The profile \"M+S+F+E\" was"
                + " inferred from the sections present in the file.";
            assertThat(m.getCode()).isEqualTo(code("Info-2052", -1, message));
            assertThat(m.getMessageType()).isEqualTo(MessageType.INFO);
            assertThat(m.getMessage()).isEqualTo(message);
        });
    }

    private EngineValidator.Outcome validate(String name) throws IOException {
        return validator.validate(tutorial(name), ValidationLevel.INFO, 500, null);
    }

    private static Path tutorial(String name) {
        // surefire runs in target/surefire-files; webapp.dir is the server module, set by the engine build
        String webapp = System.getProperty("webapp.dir");
        assertThat(webapp).as("webapp.dir is set by the engine build").isNotNull();
        return Path.of(webapp, "src", "main", "resources", "tutorial", name);
    }

    /**
     * The code as jmzTab-m writes it: the type and number in brackets, the line (-1 for none), the message and
     * jmzTab-m's line break, CRLF.
     */
    private static String code(String typeAndNumber, long line, String message) {
        return "[" + typeAndNumber + "] line " + line + ": " + message + "\r\n";
    }

    private static List<String> text(EngineValidator.Outcome outcome) {
        return outcome.messages().stream()
            .map(m -> m.getMessageType() + " " + m.getCode() + " line " + m.getLineNumber() + ": " + m.getMessage())
            .toList();
    }
}
