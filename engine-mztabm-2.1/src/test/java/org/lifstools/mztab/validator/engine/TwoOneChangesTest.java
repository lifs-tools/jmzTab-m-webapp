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
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lifstools.mztab.validator.engine.api.ValidationLevel;
import org.lifstools.mztab.validator.engine.api.ValidationMessage;
import org.lifstools.mztab.validator.engine.api.ValidationMessage.MessageType;
import org.lifstools.mztab2.cvmapping.CvParameterLookupService;
import org.lifstools.mztab2.model.StudyVariable;
import org.lifstools.mztab2.model.StudyVariableGroup;

/**
 * The rows of the table "What changed in 2.1" on the documentation's versions page ({@code VERIFIED_CHANGES} in
 * {@code versions.page.ts}), each checked against jmzTab-m 2.1 on the smallest file that shows it. A row names
 * the test here that checks it; a change that no test here can show is not in the table.
 */
class TwoOneChangesTest {

    private static final Pattern ROW_TEST = Pattern.compile("test:\\s*'(\\w+)'");
    private static final Pattern ROW_CONTRAST = Pattern.compile("contrast:\\s*'(\\w+)\\.(\\w+)'");

    @TempDir
    Path dir;

    private final EngineValidator validator = new EngineValidator(mock(CvParameterLookupService.class),
        new ObjectMapper());

    @Test
    void acceptsVersion210M() throws IOException {
        EngineValidator.Outcome outcome = validate("version-2.1.0-M.mztab");

        assertThat(outcome.mzTab()).as("parsed; messages: %s", text(outcome)).isNotNull();
        assertThat(outcome.mzTab().getMetadata().getMzTabVersion()).isEqualTo("2.1.0-M");
        assertThat(outcome.messages()).as("messages: %s", text(outcome)).noneMatch(TwoOneChangesTest::errorOrWarning);
    }

    @Test
    void reportsMissingProfileAsInfo2052() throws IOException {
        EngineValidator.Outcome outcome = validate("no-profile-2.1.mztab");

        assertThat(outcome.messages()).as("messages: %s", text(outcome)).noneMatch(TwoOneChangesTest::errorOrWarning);
        assertThat(outcome.messages()).as("messages: %s", text(outcome)).anySatisfy(m -> {
            // the profile inferred from the sections present: metadata and summary
            String message = "No mzTab-profile was declared in the metadata section. The profile \"M+S\" was inferred"
                + " from the sections present in the file.";
            assertThat(m.getCode()).isEqualTo(code("Info-2052", -1, message));
            assertThat(m.getMessageType()).isEqualTo(MessageType.INFO);
            assertThat(m.getMessage()).isEqualTo(message);
        });
    }

    @Test
    void reportsFieldOfAbsentSectionAsInfo2057() throws IOException {
        EngineValidator.Outcome outcome = validate("field-of-absent-section-2.1.mztab");

        assertThat(outcome.messages()).as("messages: %s", text(outcome)).noneMatch(TwoOneChangesTest::errorOrWarning);
        assertThat(outcome.messages()).as("messages: %s", text(outcome)).anySatisfy(m -> {
            String message = "\"small_molecule_feature-quantification_unit\" is present but the profile \"M+S\" has no"
                + " \"small molecule feature (SMF)\" section that would require it.";
            // line 27 of the file: MismatchLocator fills in the line that jmzTab-m leaves out
            assertThat(m.getCode()).isEqualTo(code("Info-2057", 27, message));
            assertThat(m.getMessageType()).isEqualTo(MessageType.INFO);
            assertThat(m.getMessage()).isEqualTo(message);
        });
    }

    @Test
    void readsStudyVariableGroups() throws IOException {
        EngineValidator.Outcome outcome = validate("study-variable-groups-2.1.mztab");

        assertThat(outcome.messages()).as("messages: %s", text(outcome)).noneMatch(TwoOneChangesTest::errorOrWarning);
        assertThat(outcome.mzTab()).as("parsed; messages: %s", text(outcome)).isNotNull();
        List<StudyVariableGroup> groups = outcome.mzTab().getMetadata().getStudyVariableGroup();
        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).getParameter().getName()).isEqualTo("treatment");
        assertThat(groups.get(0).getStudyVariableRefs()).extracting(StudyVariable::getId).containsExactly(1, 2);
    }

    @Test
    void reportsStudyVariableFactorsAsWarn2067() throws IOException {
        EngineValidator.Outcome outcome = validate("study-variable-factors-2.1.mztab");

        assertThat(outcome.messages()).as("messages: %s", text(outcome))
            .noneMatch(m -> m.getMessageType() == MessageType.ERROR);
        assertThat(outcome.messages()).as("messages: %s", text(outcome)).anySatisfy(m -> {
            String message = "\"study_variable[1]-factors\" is part of mzTab-M 2.0 and was replaced by"
                + " study_variable_group[1-n] in mzTab-M 2.1.0-M.";
            assertThat(m.getCode()).isEqualTo(code("Warn-2067", -1, message));
            assertThat(m.getMessageType()).isEqualTo(MessageType.WARN);
            assertThat(m.getMessage()).isEqualTo(message);
        });
    }

    /** Every row of the versions page names a test of this class, so the table cannot claim what no test shows. */
    @Test
    void everyRowOfTheVersionsPageNamesATestHere() throws IOException {
        // webapp.dir is the server module, set by the engine build; the page is in the frontend next to it
        String webapp = System.getProperty("webapp.dir");
        assertThat(webapp).as("webapp.dir is set by the engine build").isNotNull();
        String page = Files.readString(Path.of(webapp, "..", "frontend", "projects", "webapp", "src", "app",
            "pages", "docs", "versions.page.ts"));

        Matcher rows = ROW_TEST.matcher(page);
        List<String> named = rows.results().map(r -> r.group(1)).toList();
        List<String> tests = Arrays.stream(getClass().getDeclaredMethods())
            .filter(m -> m.isAnnotationPresent(Test.class)).map(Method::getName).toList();
        assertThat(named).as("the tests named by VERIFIED_CHANGES").isNotEmpty().doesNotHaveDuplicates();
        assertThat(tests).containsAll(named);

        // a row that claims a difference from 2.0 names the test of the 2.0 engine that shows it
        Path twoZero = Path.of(webapp, "..", "engine-mztabm-2.0", "src", "test", "java", "org", "lifstools", "mztab",
            "validator", "engine");
        for (MatchResult contrast : ROW_CONTRAST.matcher(page).results().toList()) {
            Path test = twoZero.resolve(contrast.group(1) + ".java");
            assertThat(test).as("the 2.0 engine test %s", contrast.group(1)).exists();
            assertThat(Files.readString(test)).as("%s names a test of %s", contrast.group(0), test)
                .containsPattern("@Test\\s+void " + contrast.group(2) + "\\(");
        }
    }

    private EngineValidator.Outcome validate(String name) throws IOException {
        Path file = dir.resolve(name);
        try (InputStream in = getClass().getResourceAsStream("/changes/" + name)) {
            assertThat(in).as("test file changes/%s", name).isNotNull();
            Files.write(file, in.readAllBytes());
        }
        return validator.validate(file, ValidationLevel.INFO, 100, null);
    }

    /**
     * The code as jmzTab-m writes it: the type and number in brackets, the line (-1 for none), the message and
     * jmzTab-m's line break, CRLF.
     */
    private static String code(String typeAndNumber, long line, String message) {
        return "[" + typeAndNumber + "] line " + line + ": " + message + "\r\n";
    }

    private static boolean errorOrWarning(ValidationMessage m) {
        return m.getMessageType() == MessageType.ERROR || m.getMessageType() == MessageType.WARN;
    }

    private static List<String> text(EngineValidator.Outcome outcome) {
        return outcome.messages().stream()
            .map(m -> m.getMessageType() + " " + m.getCode() + " line " + m.getLineNumber() + ": " + m.getMessage())
            .toList();
    }
}
