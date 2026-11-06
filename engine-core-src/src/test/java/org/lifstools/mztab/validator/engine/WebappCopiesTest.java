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
import org.junit.jupiter.api.Test;

/**
 * The webapp has no jmzTab-m, so it keeps copies of two files of this
 * engine's jmzTab-m: the CV mapping XSD it checks uploaded mappings against,
 * and the OpenAPI description of the mzTab-M model it publishes for this
 * line. They must stay byte for byte the files of the jmzTab-m the engine
 * runs. The webapp module is found through the surefire property
 * {@value #WEBAPP_DIR}.
 */
class WebappCopiesTest {

    static final String WEBAPP_DIR = "webapp.dir";

    @Test
    void webappXsdCopyMatchesJar() throws IOException {
        assertSameAsJar("src/main/resources/schema/CvMapping.xsd", "/schema/CvMapping.xsd");
    }

    @Test
    void webappSchemaCopyMatchesJar() throws IOException {
        assertSameAsJar("src/main/resources/mztab-schemas/" + line() + ".yml", "/mzTab_m_openapi.yml");
    }

    private static void assertSameAsJar(String copy, String resource) throws IOException {
        String dir = System.getProperty(WEBAPP_DIR);
        assertNotNull(dir, "The surefire property " + WEBAPP_DIR + " (the webapp module directory) is not set");
        Path file = Path.of(dir).resolve(copy).normalize();
        byte[] expected;
        try (InputStream in = WebappCopiesTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, resource + " is missing from the engine's jmzTab-m");
        }
        try (InputStream in = WebappCopiesTest.class.getResourceAsStream(resource)) {
            expected = in.readAllBytes();
        }
        String update = "Update " + file + " with " + resource + " of the jmzTab-m of the mzTab-M " + line()
            + " engine (" + WebappCopiesTest.class.getResource(resource) + ")";
        assertTrue(Files.isRegularFile(file), file + " is missing. " + update);
        assertArrayEquals(expected, Files.readAllBytes(file), file + " differs from the engine's jmzTab-m. " + update);
    }

    private static String line() {
        String line = System.getProperty("engine.line");
        assertNotNull(line, "The surefire property engine.line is not set");
        return line;
    }
}
