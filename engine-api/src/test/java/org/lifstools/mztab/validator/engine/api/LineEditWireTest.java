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

/**
 * Pins the JSON of the line edits an output mapping produces: the same {@code op} names and fields as the webapp's
 * {@code EditOperation}, so the webapp can apply them as they are.
 */
class LineEditWireTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void insertLine() throws IOException {
        pinned("{\"op\":\"InsertLine\",\"before\":3,\"text\":\"MTD\\tx\\ty\"}", new LineEdit.InsertLine(3, "MTD\tx\ty"));
    }

    @Test
    void insertColumn() throws IOException {
        pinned("{\"op\":\"InsertColumn\",\"header\":\"SMH\",\"position\":14,\"name\":\"abundance_assay[9]\",\"fill\":\"null\"}",
            new LineEdit.InsertColumn("SMH", 14, "abundance_assay[9]", "null"));
    }

    @Test
    void setCell() throws IOException {
        pinned("{\"op\":\"SetCell\",\"line\":5,\"cell\":2,\"text\":\"New\"}", new LineEdit.SetCell(5, 2, "New"));
    }

    @Test
    void result() throws IOException {
        MappingApplyResult r = new MappingApplyResult(List.of(new LineEdit.SetCell(1, 2, "t")),
            List.of(new Suggestion("title", "t", SuggestionState.REPLACES_EXISTING)), List.of("a note"), "MTD\ttitle\tt\n");
        String json = "{\"edits\":[{\"op\":\"SetCell\",\"line\":1,\"cell\":2,\"text\":\"t\"}],"
            + "\"suggestions\":[{\"key\":\"title\",\"value\":\"t\",\"state\":\"REPLACES_EXISTING\"}],"
            + "\"notes\":[\"a note\"],\"text\":\"MTD\\ttitle\\tt\\n\"}";
        assertEquals(json, mapper.writeValueAsString(r));
        assertEquals(r, mapper.readValue(json, MappingApplyResult.class));
    }

    @Test
    void request() throws IOException {
        String json = "{\"mapping\":\"mztab-2.0.0-M\",\"mappingVersion\":\"1.0.0\",\"parameters\":{\"study_id\":\"LCS-99999\"},"
            + "\"instance\":{\"samples\":[]},\"text\":\"\",\"provenance\":true,\"inputMapping\":\"v3-workbook 1.0.0\"}";
        MappingApplyRequest r = mapper.readValue(json, MappingApplyRequest.class);
        assertEquals("mztab-2.0.0-M", r.mapping());
        assertEquals("LCS-99999", r.parameters().get("study_id"));
        assertEquals(0, r.instance().get("samples").size());
        assertEquals("v3-workbook 1.0.0", r.inputMapping());
        assertEquals(json, mapper.writeValueAsString(r));
    }

    private void pinned(String json, LineEdit edit) throws IOException {
        assertEquals(json, mapper.writeValueAsString(edit));
        assertEquals(edit, mapper.readValue(json, LineEdit.class));
    }
}
