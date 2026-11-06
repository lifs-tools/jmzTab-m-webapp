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
package org.lifstools.mztab.validator.webapp.validationprofile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.lifstools.mztab.validator.webapp.service.validation.InvalidMappingFileException;
import org.lifstools.mztab.validator.webapp.service.validation.SafeCvMappingLoader;
import org.springframework.core.io.DefaultResourceLoader;

class ValidationProfileCatalogTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final DefaultResourceLoader loader = new DefaultResourceLoader();

    private ValidationProfileCatalog catalog() {
        return new ValidationProfileCatalog(mapper, loader, "classpath:/validation-profiles/index.json");
    }

    @Test
    void catalogListsTheDefaultProfileOfEachLine() {
        List<ValidationProfile> latest = catalog().latest();
        assertEquals(2, latest.size());
        assertEquals("mztab-m-2.0-default", latest.get(0).id());
        assertEquals("1.1.0", latest.get(0).version());
        assertEquals("mztab-m-2.1-default", latest.get(1).id());
        assertEquals("1.0.0", latest.get(1).version());
        assertEquals(List.of("2.1.0-M"), latest.get(1).mzTabVersions());
        assertEquals("1.0.0", catalog().find("mztab-m-2.0-default", "1.0.0").orElseThrow().version());
    }

    @Test
    void forLinesKeepsProfilesWithAVersionOfAnAvailableLine() {
        assertEquals(List.of("mztab-m-2.0-default"),
            catalog().forLines(java.util.Set.of("2.0")).stream().map(ValidationProfile::id).toList());
        assertEquals(List.of("mztab-m-2.1-default"),
            catalog().forLines(java.util.Set.of("2.1")).stream().map(ValidationProfile::id).toList());
        assertEquals(2, catalog().forLines(java.util.Set.of("2.0", "2.1")).size());
        assertTrue(catalog().forLines(java.util.Set.of("3.0")).isEmpty());
    }

    @Test
    void everyCatalogMappingValidatesAgainstXsd() throws Exception {
        ValidationProfileCatalog catalog = catalog();
        for (ValidationProfile p : catalog.all()) {
            URL url = catalog.mappingUrl(p);
            try (InputStream in = url.openStream()) {
                SafeCvMappingLoader.validateAgainstSchema(in.readAllBytes());
            }
        }
    }

    @Test
    void unknownProfileIsEmpty() {
        assertTrue(catalog().find("nope", null).isEmpty());
        assertTrue(catalog().find("mztab-m-2.0-default", "9.9.9").isEmpty());
    }

    @Test
    void latestPicksHighestVersion() {
        ValidationProfileCatalog c = new ValidationProfileCatalog(mapper, loader,
            "classpath:/validation-profiles-test/index.json");
        assertEquals(1, c.latest().size());
        assertEquals("1.10.0", c.latest().get(0).version());
        assertEquals("1.10.0", c.find("mztab-m-2.0-default", null).orElseThrow().version());
        assertEquals("1.0.0", c.find("mztab-m-2.0-default", "1.0.0").orElseThrow().version());
    }

    @Test
    void defaultForIsTheLatestDefaultProfileOfTheLine() {
        ValidationProfileCatalog c = new ValidationProfileCatalog(mapper, loader,
            "classpath:/validation-profiles-test/index.json");
        assertEquals("1.10.0", c.defaultFor("2.0").orElseThrow().version());
        assertEquals("mztab-m-2.0-default", c.defaultFor("2.0").orElseThrow().id());
        assertTrue(c.defaultFor("2.1").isEmpty());
        assertEquals("1.1.0", catalog().defaultFor("2.0").orElseThrow().version());
        assertEquals("1.0.0", catalog().defaultFor("2.1").orElseThrow().version());
    }

    @Test
    void defaultForLineIsLatestVersion() {
        assertEquals("1.1.0", catalog().defaultFor("2.0").orElseThrow().version());
    }

    @Test
    void schemaRejectsEmptyCvMapping() {
        assertThrows(InvalidMappingFileException.class,
            () -> SafeCvMappingLoader.validateAgainstSchema("<CvMapping/>".getBytes(StandardCharsets.UTF_8)));
    }
}
