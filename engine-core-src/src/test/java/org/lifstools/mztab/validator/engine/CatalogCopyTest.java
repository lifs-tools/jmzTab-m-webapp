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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The default validation profile of this engine's line in the webapp's catalog
 * must be the CV mapping jmzTab-m bundles. The latest catalog version is
 * compared; catalog versions are immutable, so a changed bundled mapping needs
 * a new version file. The webapp module is found through the surefire
 * property {@value WebappCopiesTest#WEBAPP_DIR}.
 */
class CatalogCopyTest {

    private static final Pattern VERSION_FILE = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)\\.xml$");

    @Test
    void defaultProfileCopyMatchesBundledMapping() throws IOException {
        String line = System.getProperty("engine.line");
        assertNotNull(line, "The surefire property engine.line is not set");
        String dir = System.getProperty(WebappCopiesTest.WEBAPP_DIR);
        assertNotNull(dir, "The surefire property " + WebappCopiesTest.WEBAPP_DIR + " (the webapp module directory) is not set");
        Path profile = Path.of(dir).resolve("src/main/resources/validation-profiles/mztab-m-" + line + "-default").normalize();
        String hint = "jmzTab-m's bundled mapping changed: add a new catalog version mztab-m-" + line
            + "-default/<next>.xml";
        assertTrue(Files.isDirectory(profile), profile + " is missing. " + hint);
        Path latest;
        try (Stream<Path> files = Files.list(profile)) {
            latest = files.filter(f -> VERSION_FILE.matcher(f.getFileName().toString()).matches())
                .max((a, b) -> compare(a, b)).orElse(null);
        }
        assertNotNull(latest, "No version file in " + profile + ". " + hint);
        byte[] bundled;
        try (InputStream in = CatalogCopyTest.class.getResourceAsStream("/mappings/mzTab-M-mapping.xml")) {
            assertNotNull(in, "/mappings/mzTab-M-mapping.xml is missing from the engine's jmzTab-m");
            bundled = in.readAllBytes();
        }
        assertArrayEquals(bundled, Files.readAllBytes(latest), latest + " differs from the bundled mapping. " + hint);
    }

    private static int compare(Path a, Path b) {
        Matcher x = VERSION_FILE.matcher(a.getFileName().toString());
        Matcher y = VERSION_FILE.matcher(b.getFileName().toString());
        x.matches();
        y.matches();
        for (int i = 1; i <= 3; i++) {
            int c = Integer.compare(Integer.parseInt(x.group(i)), Integer.parseInt(y.group(i)));
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }
}
