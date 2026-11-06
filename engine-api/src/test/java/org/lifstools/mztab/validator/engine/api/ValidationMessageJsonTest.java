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
package org.lifstools.mztab.validator.engine.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class ValidationMessageJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private ValidationMessage full() {
        ValidationLocation location = new ValidationLocation();
        location.setSection(ValidationLocation.Section.SML);
        location.setRowIndex(3);
        location.setColumn("smallmolecule_identifier");
        location.setMetadataKey("assay[1]-sample_ref");
        location.setMetadataGroup("assay[1]");
        location.setElementPath("/metadata/assay/0");
        ValidationMessage message = new ValidationMessage();
        message.setCode("0042");
        message.setCategory(ValidationMessage.Category.CROSS_CHECK);
        message.setMessageType(ValidationMessage.MessageType.WARN);
        message.setMessage("Something is odd");
        message.setLineNumber(17L);
        message.setSuggestion("Fix it");
        message.setRuleId("rule-1");
        message.setLocation(location);
        return message;
    }

    @Test
    void serializesLikeJmzTabM() throws IOException {
        String expected;
        try (InputStream in = getClass().getResourceAsStream("/validation-message.json")) {
            expected = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        }
        // character for character, including the field order
        assertEquals(expected, mapper.writeValueAsString(full()));
    }

    @Test
    void omitsNullsLikeJmzTabM() throws IOException {
        assertEquals("{\"code\":\"\",\"category\":\"format\",\"message_type\":\"error\",\"message\":\"x\",\"line_number\":-1}",
            mapper.writeValueAsString(ValidationMessage.error("x")));
        assertEquals("info", mapper.readTree(mapper.writeValueAsString(ValidationMessage.info("y"))).get("message_type").asText());
    }

    @Test
    void readsTheFixture() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/validation-message.json")) {
            assertEquals(full(), mapper.readValue(in, ValidationMessage.class));
        }
    }

    @Test
    void roundTripsAllEnumValues() throws IOException {
        for (ValidationMessage.Category c : ValidationMessage.Category.values()) {
            for (ValidationMessage.MessageType t : ValidationMessage.MessageType.values()) {
                for (ValidationLocation.Section s : ValidationLocation.Section.values()) {
                    ValidationMessage m = full();
                    m.setCategory(c);
                    m.setMessageType(t);
                    m.getLocation().setSection(s);
                    JsonNode tree = mapper.readTree(mapper.writeValueAsString(m));
                    assertEquals(c.name().toLowerCase(Locale.ROOT), tree.get("category").asText());
                    assertEquals(t.name().toLowerCase(Locale.ROOT), tree.get("message_type").asText());
                    assertEquals(s.name().toLowerCase(Locale.ROOT), tree.get("location").get("section").asText());
                    assertEquals(m, mapper.readValue(mapper.writeValueAsString(m), ValidationMessage.class));
                }
            }
        }
    }
}
