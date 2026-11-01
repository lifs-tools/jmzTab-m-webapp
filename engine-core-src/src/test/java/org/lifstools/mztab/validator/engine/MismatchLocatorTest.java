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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lifstools.mztab.validator.engine.api.ValidationLevel;
import org.lifstools.mztab.validator.engine.api.ValidationLocation;
import org.lifstools.mztab.validator.engine.api.ValidationMessage;
import org.lifstools.mztab2.cvmapping.CvParameterLookupService;
import org.mockito.Mockito;

/**
 * Info-2057 comes from jmzTab-m without a line or a location; the engine fills them in when the name the message
 * quotes is found exactly once in the file.
 */
class MismatchLocatorTest {

    @TempDir
    Path dir;

    private static ValidationMessage message(String name) {
        ValidationMessage m = new ValidationMessage();
        m.setCode("[Info-2057] line -1: \"" + name + "\" is present but the profile has no section.\r\n");
        m.setMessage("\"" + name + "\" is present but the profile \"M+S\" has no section that would require it.");
        m.setLineNumber(-1L);
        return m;
    }

    @Test
    void locatesAMetadataKey() {
        ValidationMessage m = message("small_molecule_feature-quantification_unit");

        MismatchLocator.locate(List.of(m), "MTD\tmzTab-version\t2.0.0-M\nMTD\tx-y\t1\n"
            + "MTD\tsmall_molecule_feature-quantification_unit\t[UO, UO:1, u, ]\nSMH\ta\tb\n");

        assertEquals(3L, m.getLineNumber());
        assertEquals(ValidationLocation.Section.MTD, m.getLocation().getSection());
        assertEquals("small_molecule_feature-quantification_unit", m.getLocation().getMetadataKey());
        assertNull(m.getLocation().getMetadataGroup());
        assertEquals("[Info-2057] line 3: \"small_molecule_feature-quantification_unit\" is present but the "
            + "profile has no section.\r\n", m.getCode());
    }

    @Test
    void locatesAKeyOfAnIndexedGroup() {
        ValidationMessage m = message("study_variable[2]-factors");

        MismatchLocator.locate(List.of(m), "MTD\tmzTab-version\t2.0.0-M\nMTD\tstudy_variable[2]-factors\tx\n");

        assertEquals("study_variable", m.getLocation().getMetadataGroup());
    }

    @Test
    void locatesATableColumn() {
        ValidationMessage m = message("opt_global_x");

        MismatchLocator.locate(List.of(m), "MTD\ta\tb\nSMH\tSML_ID\tSMF_ID_REFS\nSML\t1\t2\n"
            + "SFH\tSMF_ID\topt_global_x\n");

        assertEquals(4L, m.getLineNumber());
        assertEquals(ValidationLocation.Section.SFH, m.getLocation().getSection());
        assertEquals("opt_global_x", m.getLocation().getColumn());
        assertNull(m.getLocation().getRowIndex());
    }

    @Test
    void leavesAMessageWhoseNameIsNotFound() {
        ValidationMessage m = message("nope");

        MismatchLocator.locate(List.of(m), "MTD\ta\tb\nSMH\tSML_ID\n");

        assertEquals(-1L, m.getLineNumber());
        assertNull(m.getLocation());
    }

    @Test
    void leavesAMessageWhoseNameIsFoundTwice() {
        ValidationMessage m = message("dup");

        MismatchLocator.locate(List.of(m), "MTD\tdup\tb\nSMH\tSML_ID\tdup\n");

        assertEquals(-1L, m.getLineNumber());
        assertNull(m.getLocation());
    }

    @Test
    void leavesOtherCodesAndLocatedMessagesAlone() {
        ValidationMessage other = message("a");
        other.setCode("[Info-2058] line -1: \"a\" is present");
        ValidationMessage located = message("a");
        ValidationLocation loc = new ValidationLocation();
        loc.setSection(ValidationLocation.Section.SML);
        located.setLocation(loc);

        MismatchLocator.locate(List.of(other, located), "MTD\ta\tb\n");

        assertNull(other.getLocation());
        assertEquals(-1L, other.getLineNumber());
        assertEquals(ValidationLocation.Section.SML, located.getLocation().getSection());
        assertNull(located.getLocation().getMetadataKey());
    }

    @Test
    void validateLocatesTheMismatchOfARealFile() throws IOException {
        String webapp = System.getProperty("webapp.dir");
        assertNotNull(webapp, "webapp.dir is set by the engine build");
        String text = new String(Fixtures.withVersion(Files.readAllBytes(Path.of(webapp)
            .resolve("src/test/resources/workingcopy/engine-remove-mismatched-field.mzTab")),
            Fixtures.engineVersion()), StandardCharsets.UTF_8);
        Path file = Files.writeString(dir.resolve("mismatch.mztab"), text);
        EngineValidator validator = new EngineValidator(Mockito.mock(CvParameterLookupService.class),
            new ObjectMapper());

        List<ValidationMessage> messages = validator.validate(file, ValidationLevel.INFO, 100, null).messages();

        ValidationMessage m = messages.stream().filter(x -> x.getCode().contains("2057")).findFirst()
            .orElseThrow(() -> new AssertionError(messages.toString()));
        String[] lines = text.split("\n");
        assertEquals("MTD\tsmall_molecule_feature-quantification_unit",
            lines[(int) (m.getLineNumber() - 1)].substring(0,
                "MTD\tsmall_molecule_feature-quantification_unit".length()));
        assertEquals("small_molecule_feature-quantification_unit", m.getLocation().getMetadataKey());
    }
}
