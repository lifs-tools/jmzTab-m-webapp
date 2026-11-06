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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Pins the JSON that engines write and the webapp reads: the NDJSON event
 * {@code type} names, the field names, and the lower-case error codes.
 */
class EngineWireFormatTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void outcomeEvent() throws IOException {
        pinned("{\"type\":\"outcome\",\"parsed\":true,\"mzTabVersion\":\"2.0.0-M\",\"messages\":"
            + "[{\"code\":\"\",\"category\":\"format\",\"message_type\":\"info\",\"message\":\"note\",\"line_number\":-1}]}",
            new ValidationEvent.Outcome(true, "2.0.0-M", List.of(ValidationMessage.info("note"))));
    }

    @Test
    void metadataEvent() throws IOException {
        pinned("{\"type\":\"metadata\",\"line\":1,\"key\":\"mzTab-version\",\"value\":\"2.0.0-M\"}",
            new ValidationEvent.MetadataLine(1, "mzTab-version", "2.0.0-M"));
    }

    @Test
    void tableEvent() throws IOException {
        pinned("{\"type\":\"table\",\"section\":\"SML\",\"header\":[\"SML_ID\",\"SMF_ID_REFS\"]}",
            new ValidationEvent.TableHeader("SML", List.of("SML_ID", "SMF_ID_REFS")));
    }

    @Test
    void rowEvent() throws IOException {
        pinned("{\"type\":\"row\",\"section\":\"SML\",\"cells\":[\"1\",\"null\"]}",
            new ValidationEvent.TableRowCells("SML", List.of("1", "null")));
    }

    @Test
    void endEvent() throws IOException {
        pinned("{\"type\":\"end\",\"sections\":{\"metadataLines\":12,\"sml\":{\"rows\":3,\"columns\":20},"
            + "\"smf\":null,\"sme\":null}}",
            new ValidationEvent.End(new SectionSizes(12, new TableDims(3, 20), null, null)));
        pinned("{\"type\":\"end\",\"sections\":null}", new ValidationEvent.End(null));
    }

    @ParameterizedTest
    @EnumSource(EngineErrorCode.class)
    void errorCodesAreLowerCase(EngineErrorCode code) throws IOException {
        String json = "{\"code\":\"" + code.name().toLowerCase(java.util.Locale.ROOT) + "\",\"message\":\"m\"}";
        assertEquals(json, mapper.writeValueAsString(new EngineError(code, "m")));
        assertEquals(new EngineError(code, "m"), mapper.readValue(json, EngineError.class));
    }

    @Test
    void errorCodeNames() {
        assertEquals(List.of("unsupported_version", "busy", "invalid_request", "file_too_large", "not_json",
            "internal"), java.util.Arrays.stream(EngineErrorCode.values()).map(EngineErrorCode::value).toList());
    }

    /** Written exactly as {@code json}, and read back as an equal event of the same type. */
    private void pinned(String json, ValidationEvent event) throws IOException {
        assertEquals(json, mapper.writeValueAsString(event));
        assertEquals(event, mapper.readValue(json, ValidationEvent.class));
    }
}
