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
package org.lifstools.mztab.validator.engine.skeleton;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lifstools.mztab.submission.mapping.MappingRequestException;
import org.lifstools.mztab.validator.engine.EngineValidator;
import org.lifstools.mztab.validator.engine.api.SkeletonRequest;
import org.lifstools.mztab.validator.engine.api.ValidationLevel;
import org.lifstools.mztab.validator.engine.api.ValidationMessage;
import org.lifstools.mztab2.cvmapping.CvParameterLookupService;

/** The skeleton of a new file. The shared tests run in both engines, so each checks the version it serves. */
class SkeletonWriterTest {

    private static final Set<String> BOTH = Set.of("2.0.0-M", "2.1.0-M");

    @TempDir
    Path dir;

    @Test
    void twoZero() {
        assertEquals("MTD\tmzTab-version\t2.0.0-M\nMTD\tmzTab-ID\tmy-study\n",
            SkeletonWriter.write(new SkeletonRequest("2.0.0-M", null, "my-study"), BOTH));
    }

    @Test
    void twoOneWithProfile() {
        assertEquals("MTD\tmzTab-version\t2.1.0-M\nMTD\tmzTab-ID\tmy-study\nMTD\tmzTab-profile\tM+S\n",
            SkeletonWriter.write(new SkeletonRequest("2.1.0-M", "M+S", "my-study"), BOTH));
    }

    @Test
    void everyContentProfileIsWritten() {
        for (String p : new String[] {"M", "M+S", "M+F", "M+F+E", "M+S+F+E"}) {
            assertTrue(SkeletonWriter.write(new SkeletonRequest("2.1.0-M", p, "a"), BOTH)
                .endsWith("MTD\tmzTab-profile\t" + p + "\n"));
        }
    }

    @Test
    void refusals() {
        assertRefused(new SkeletonRequest("2.0.0-M", "M", "a"), BOTH);
        assertRefused(new SkeletonRequest("2.1.0-M", null, "a"), BOTH);
        assertRefused(new SkeletonRequest("2.1.0-M", "M+X", "a"), BOTH);
        assertRefused(new SkeletonRequest("2.0.0-M", null, "a".repeat(65)), BOTH);
        assertRefused(new SkeletonRequest("2.0.0-M", null, "-a"), BOTH);
        assertRefused(new SkeletonRequest("2.0.0-M", null, "a\tb"), BOTH);
        assertRefused(new SkeletonRequest("2.0.0-M", null, null), BOTH);
        assertRefused(new SkeletonRequest(null, null, "a"), BOTH);
        assertRefused(new SkeletonRequest("2.1.0-M", "M", "a"), Set.of("2.0.0-M"));
        assertRefused(new SkeletonRequest("1.0.0", null, "a"), BOTH);
    }

    @Test
    void anIdOfExactlyTheLongestLengthIsWritten() {
        String id = "a" + "b".repeat(63);
        assertTrue(SkeletonWriter.write(new SkeletonRequest("2.0.0-M", null, id), BOTH).contains(id + "\n"));
    }

    /** The skeletons of the version this engine serves are parsed by its own validator; the other line's are not its to read. */
    @Test
    void parses() throws IOException {
        String served = engineVersion();
        EngineValidator validator = new EngineValidator(mock(CvParameterLookupService.class), new ObjectMapper());
        String[] profiles = served.startsWith("2.0") ? new String[] {null}
            : new String[] {"M", "M+S", "M+F", "M+F+E", "M+S+F+E"};
        for (String profile : profiles) {
            String text = SkeletonWriter.write(new SkeletonRequest(served, profile, "my-study"), Set.of(served));
            Path file = Files.writeString(dir.resolve("skeleton-" + profile + ".mztab"), text, StandardCharsets.UTF_8);
            EngineValidator.Outcome outcome = validator.validate(file, ValidationLevel.ERROR, 100, null);
            // The skeleton is incomplete by design (no software, assay, cv ...), so the parser reports what is missing
            // (LOGICAL) and gives no model; it must not report a FORMAT fault, i.e. the text itself is read.
            assertTrue(outcome.messages().stream().noneMatch(m -> m.getCategory() == ValidationMessage.Category.FORMAT),
                "not parsed: " + served + " " + profile + " " + outcome.messages());
            assertTrue(outcome.messages().stream().anyMatch(m -> m.getMessage().contains("MUST be defined")),
                "the parser did not read the metadata: " + outcome.messages());
        }
    }

    private static String engineVersion() throws IOException {
        Properties properties = new Properties();
        try (InputStream in = SkeletonWriterTest.class.getResourceAsStream("/application.properties")) {
            properties.load(in);
        }
        return properties.getProperty("engine.mztab-versions").split(",")[0].strip();
    }

    private static void assertRefused(SkeletonRequest r, Set<String> versions) {
        assertThrows(MappingRequestException.class, () -> SkeletonWriter.write(r, versions), r.toString());
    }
}
