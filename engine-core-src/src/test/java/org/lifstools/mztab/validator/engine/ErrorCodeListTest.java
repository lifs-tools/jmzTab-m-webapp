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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import uk.ac.ebi.pride.jmztab2.utils.errors.CrossCheckErrorType;
import uk.ac.ebi.pride.jmztab2.utils.errors.FormatErrorType;
import uk.ac.ebi.pride.jmztab2.utils.errors.LogicalErrorType;
import uk.ac.ebi.pride.jmztab2.utils.errors.MZTabErrorType;
import uk.ac.ebi.pride.jmztab2.utils.errors.MZTabErrorTypeMap;

/**
 * Guards the webapp's committed list of this engine line's error codes, which
 * the webapp serves without a jmzTab-m dependency. Fails when the list differs
 * from jmzTab-m's; with {@code -Derrorcodes.update=true} it rewrites the file.
 */
class ErrorCodeListTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void committedListMatchesThisEngine() throws Exception {
        String line = System.getProperty("engine.line");
        assertNotNull(line, "The surefire property engine.line is not set");
        String webapp = System.getProperty("webapp.dir");
        assertNotNull(webapp, "The surefire property webapp.dir is not set");
        Path file = Path.of(webapp).resolve("src/main/resources/guidance/error-codes-" + line + ".json").normalize();
        String stale = file + " is stale: run ./mvnw -pl engine-mztabm-" + line + " -am test "
            + "-Dtest=ErrorCodeListTest -Derrorcodes.update=true -Dsurefire.failIfNoSpecifiedTests=false";

        Map<Integer, MZTabErrorType> types = new TreeMap<>(new MZTabErrorTypeMap().getTypeMap());
        Map<Integer, String> keywords = keywords();
        ArrayNode list = MAPPER.createArrayNode();
        types.forEach((code, type) -> {
            ObjectNode entry = list.addObject();
            entry.put("code", code);
            entry.put("level", type.getLevel().name());
            entry.put("keyword", keywords.get(code));
        });
        String expected = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(list)
            .replace("\r\n", "\n") + "\n";

        if (Boolean.parseBoolean(System.getProperty("errorcodes.update"))) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, expected, StandardCharsets.UTF_8);
            return;
        }
        assertTrue(Files.exists(file), stale);
        assertEquals(expected, Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n"), stale);
    }

    /** The name of each error type's constant, which jmzTab-m uses as its keyword, by code. */
    private static Map<Integer, String> keywords() throws IllegalAccessException {
        Map<Integer, String> keywords = new TreeMap<>();
        for (Class<?> holder : new Class<?>[]{FormatErrorType.class, LogicalErrorType.class,
            CrossCheckErrorType.class}) {
            for (Field field : holder.getFields()) {
                if (MZTabErrorType.class.isAssignableFrom(field.getType())
                    && Modifier.isStatic(field.getModifiers())) {
                    keywords.put(((MZTabErrorType) field.get(null)).getCode(), field.getName());
                }
            }
        }
        return keywords;
    }
}
