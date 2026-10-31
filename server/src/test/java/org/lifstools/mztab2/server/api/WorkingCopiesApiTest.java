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
package org.lifstools.mztab2.server.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.lifstools.mztab.validator.webapp.Application;
import org.lifstools.mztab.validator.webapp.AwaitAsyncValidations;
import org.lifstools.mztab.validator.webapp.EngineTestSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** {@code /rest/v2/working-copies} with the real mzTab-M 2.0 engine. */
@SpringBootTest(classes = Application.class, properties = {"eureka.client.enabled=false",
    "spring.cloud.discovery.enabled=false"})
@AutoConfigureMockMvc
@ExtendWith(AwaitAsyncValidations.class)
class WorkingCopiesApiTest {

    private static final String BASE = "/rest/v2/working-copies";

    @TempDir
    static Path storageRoot;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("storage.location", () -> storageRoot.toString());
        EngineTestSupport.register(registry);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    static byte[] example() throws Exception {
        try (InputStream in = WorkingCopiesApiTest.class
            .getResourceAsStream("/metabolomics/lipidomics-example.mzTab")) {
            return in.readAllBytes();
        }
    }

    /** Uploads a file for validation and waits until the validation has ended. */
    String validation(String name, byte[] content) throws Exception {
        MvcResult r = mvc.perform(multipart("/rest/v2/validations")
            .file(new MockMultipartFile("file", name, "text/plain", content)))
            .andExpect(status().isAccepted()).andReturn();
        String id = json.readTree(r.getResponse().getContentAsString()).get("id").asText();
        long end = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < end) {
            JsonNode n = json.readTree(mvc.perform(get("/rest/v2/validations/" + id))
                .andReturn().getResponse().getContentAsString());
            String s = n.get("status").asText();
            if (s.equals("FINISHED") || s.equals("FAILED")) {
                return id;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("validation did not end");
    }

    JsonNode create(String validation) throws Exception {
        return json.readTree(mvc.perform(post(BASE).param("validation", validation).param("encoding", "UTF_8"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    static String edits(long revision, String operations) {
        return "{\"revision\":" + revision + ",\"operations\":[" + operations + "]}";
    }

    @Test
    void createReadEditUndoDownloadRoundTrip() throws Exception {
        byte[] original = example();
        String id = validation("round.mzTab", original);

        JsonNode created = create(id);
        assertEquals(id, created.get("id").asText());
        assertEquals(0, created.get("revision").asLong());
        assertEquals("round.mzTab", created.get("fileName").asText());
        assertTrue(Instant.parse(created.get("expiresAt").asText()).isAfter(Instant.now()));
        mvc.perform(post(BASE).param("validation", id)).andExpect(status().isOk())
            .andExpect(jsonPath("$.revision").value(0));
        mvc.perform(get(BASE + "/" + id)).andExpect(status().isOk())
            .andExpect(jsonPath("$.lineCount").value(created.get("lineCount").asLong()));

        mvc.perform(get(BASE + "/" + id + "/lines").param("from", "3").param("count", "2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].number").value(3))
            .andExpect(jsonPath("$[0].section").value("MTD"))
            .andExpect(jsonPath("$[0].cells[1]").value("mzTab-ID"))
            .andExpect(jsonPath("$[1].number").value(4));
        mvc.perform(get(BASE + "/" + id + "/metadata")).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].name").value("general"));
        mvc.perform(get(BASE + "/" + id + "/metadata/general").param("key", "mzTab-ID")).andExpect(status().isOk())
            .andExpect(jsonPath("$.entries[1].key").value("mzTab-ID"))
            .andExpect(jsonPath("$.entries[1].lineNumber").value(3))
            .andExpect(jsonPath("$.entries[1].value").value("ISAS-2018-1234"));
        MvcResult table = mvc.perform(get(BASE + "/" + id + "/tables/sml").param("size", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.rows[0].index").value(0))
            .andExpect(jsonPath("$.rows[0].lineNumber").isNumber()).andReturn();
        long smlLine = json.readTree(table.getResponse().getContentAsString()).at("/rows/0/lineNumber").asLong();
        mvc.perform(get(BASE + "/" + id + "/lines").param("from", Long.toString(smlLine)).param("count", "1"))
            .andExpect(jsonPath("$[0].section").value("SML"));

        mvc.perform(post(BASE + "/" + id + "/edits").contentType(MediaType.APPLICATION_JSON)
            .content(edits(0, "{\"op\":\"SetMetadataValue\",\"line\":3,\"value\":\"edited\"}")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.revision").value(1))
            .andExpect(jsonPath("$.canUndo").value(true));
        mvc.perform(get(BASE + "/" + id + "/lines").param("from", "3").param("count", "1"))
            .andExpect(jsonPath("$[0].cells[2]").value("edited"));
        mvc.perform(post(BASE + "/" + id + "/undo").param("revision", "1")).andExpect(status().isOk())
            .andExpect(jsonPath("$.revision").value(2))
            .andExpect(jsonPath("$.canRedo").value(true));

        MvcResult file = mvc.perform(get(BASE + "/" + id + "/file").param("format", "tsv"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Disposition", "attachment; filename=\"round.mzTab\""))
            .andReturn();
        assertArrayEquals(original, file.getResponse().getContentAsByteArray());
        MvcResult upload = mvc.perform(get(BASE + "/" + id + "/original")).andExpect(status().isOk()).andReturn();
        assertArrayEquals(original, upload.getResponse().getContentAsByteArray());

        mvc.perform(post(BASE + "/" + id + "/validations")).andExpect(status().isAccepted())
            .andExpect(jsonPath("$.validation.semantic").value(false));
        mvc.perform(delete(BASE + "/" + id)).andExpect(status().isNoContent());
        mvc.perform(get(BASE + "/" + id)).andExpect(status().isNotFound());
        mvc.perform(get("/rest/v2/validations/" + id)).andExpect(status().isOk());
    }

    @Test
    void staleEditIs409() throws Exception {
        String id = validation("stale.mzTab", example());
        create(id);
        String op = "{\"op\":\"SetMetadataValue\",\"line\":3,\"value\":\"x\"}";
        mvc.perform(post(BASE + "/" + id + "/edits").contentType(MediaType.APPLICATION_JSON).content(edits(0, op)))
            .andExpect(status().isOk());
        mvc.perform(post(BASE + "/" + id + "/edits").contentType(MediaType.APPLICATION_JSON).content(edits(0, op)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("The copy is at revision 1, not 0"));
        mvc.perform(post(BASE + "/" + id + "/undo").param("revision", "0")).andExpect(status().isConflict());
        mvc.perform(post(BASE + "/" + id + "/redo").param("revision", "1")).andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("There is nothing to redo."));
        mvc.perform(get(BASE + "/" + id + "/file").param("format", "json")).andExpect(status().isConflict());
        mvc.perform(post(BASE + "/" + id + "/edits").contentType(MediaType.APPLICATION_JSON)
            .content(edits(1, "{\"op\":\"DeleteLine\",\"line\":100000}")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").isString());
        mvc.perform(post(BASE + "/" + id + "/edits").contentType(MediaType.APPLICATION_JSON)
            .content("{\"revision\":1,\"operations\":[{\"op\":\"NoSuchOp\"}]}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void negativeRevisionIs400() throws Exception {
        String id = validation("negative.mzTab", example());
        create(id);
        String op = "{\"op\":\"SetMetadataValue\",\"line\":3,\"value\":\"x\"}";
        mvc.perform(post(BASE + "/" + id + "/edits").contentType(MediaType.APPLICATION_JSON).content(edits(-1, op)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("revision must not be negative."));
        mvc.perform(post(BASE + "/" + id + "/undo").param("revision", "-1")).andExpect(status().isBadRequest());
        mvc.perform(post(BASE + "/" + id + "/redo").param("revision", "-1")).andExpect(status().isBadRequest());
        mvc.perform(get(BASE + "/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(0));
    }

    @Test
    void notUtf8Is422WithCode() throws Exception {
        String text = new String(example(), StandardCharsets.UTF_8).replace("ISAS-2018-1234", "ISÄS-2018-1234");
        String id = validation("latin1.mzTab", text.getBytes(StandardCharsets.ISO_8859_1));
        mvc.perform(post(BASE).param("validation", id)).andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.error").value("not_utf8"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("line 3")));
        mvc.perform(post(BASE).param("validation", id).param("encoding", "WINDOWS_1252"))
            .andExpect(status().isCreated());
    }

    @Test
    void parameterBounds() throws Exception {
        String id = validation("bounds.mzTab", example());
        create(id);
        String copy = BASE + "/" + id;
        mvc.perform(get(copy + "/lines").param("from", "1").param("count", "0")).andExpect(status().isBadRequest());
        mvc.perform(get(copy + "/lines").param("from", "1").param("count", "1001"))
            .andExpect(status().isBadRequest());
        mvc.perform(get(copy + "/lines").param("from", "1").param("count", "1000")).andExpect(status().isOk());
        mvc.perform(get(copy + "/lines").param("from", "0").param("count", "1")).andExpect(status().isBadRequest());
        long lines = json.readTree(mvc.perform(get(copy)).andReturn().getResponse().getContentAsString())
            .get("lineCount").asLong();
        mvc.perform(get(copy + "/lines").param("from", Long.toString(lines + 1)).param("count", "5"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get(copy + "/lines").param("from", Long.toString(lines + 2)).param("count", "5"))
            .andExpect(status().isBadRequest());
        mvc.perform(get(copy + "/lines").param("from", "x").param("count", "5")).andExpect(status().isBadRequest());
        mvc.perform(get(copy + "/tables/sml").param("size", "201")).andExpect(status().isBadRequest());
        mvc.perform(get(copy + "/tables/sml").param("size", "0")).andExpect(status().isBadRequest());
        mvc.perform(get(copy + "/tables/sml").param("page", "-1")).andExpect(status().isBadRequest());
        mvc.perform(get(copy + "/tables/sml").param("sort", "no-such-column")).andExpect(status().isBadRequest());
        mvc.perform(get(copy + "/tables/sml").param("filter", "nocolon")).andExpect(status().isBadRequest());
        mvc.perform(get(copy + "/tables/xyz")).andExpect(status().isBadRequest());
        mvc.perform(get(copy + "/metadata/general").param("size", "201")).andExpect(status().isBadRequest());
        mvc.perform(post(BASE).param("validation", id).param("encoding", "UTF_16")).andExpect(status().isBadRequest());
        mvc.perform(post(BASE).param("validation", "not-a-uuid")).andExpect(status().isBadRequest());
        mvc.perform(get(BASE + "/not-a-uuid")).andExpect(status().isBadRequest());
        mvc.perform(get(copy + "/file").param("format", "xml")).andExpect(status().isBadRequest());
        mvc.perform(post(copy + "/undo")).andExpect(status().isBadRequest());
        mvc.perform(post(copy + "/validations").param("semantic", "maybe")).andExpect(status().isBadRequest());
    }

    @Test
    void unknownCopyIs404() throws Exception {
        String unknown = UUID.randomUUID().toString();
        mvc.perform(get(BASE + "/" + unknown)).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.message").value("Unknown or expired working copy."));
        mvc.perform(delete(BASE + "/" + unknown)).andExpect(status().isNotFound());
        mvc.perform(get(BASE + "/" + unknown + "/lines").param("from", "1").param("count", "1"))
            .andExpect(status().isNotFound());
        mvc.perform(get(BASE + "/" + unknown + "/original")).andExpect(status().isNotFound());
        mvc.perform(post(BASE).param("validation", unknown)).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.message").value("Unknown or expired validation."));
        String id = validation("no-copy.mzTab", example());
        mvc.perform(get(BASE + "/" + id)).andExpect(status().isNotFound());
        mvc.perform(post(BASE + "/" + id + "/validations")).andExpect(status().isNotFound());
    }

    @Test
    void quickFixPreviewEndpoint() throws Exception {
        String text = new String(example(), StandardCharsets.UTF_8)
            .replace("[MS, MS:1000624, inductive detector, ]", "[, , , ]");
        String id = validation("quickfix.mzTab", text.getBytes(StandardCharsets.UTF_8));
        create(id);
        String url = BASE + "/" + id + "/quick-fixes/remove-empty-parameter";

        mvc.perform(get(url).param("message", "0")).andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("Validate the current revision first."));
        mvc.perform(post(BASE + "/" + id + "/validations")).andExpect(status().isAccepted());
        JsonNode copy;
        long end = System.currentTimeMillis() + 60_000;
        do {
            Thread.sleep(50);
            copy = json.readTree(mvc.perform(get(BASE + "/" + id)).andReturn().getResponse().getContentAsString());
        } while (!"DONE".equals(copy.at("/validation/status").asText()) && System.currentTimeMillis() < end);
        int message = -1;
        for (int i = 0; i < copy.at("/validation/messages").size(); i++) {
            if (copy.at("/validation/messages/" + i + "/message/code").asText().contains("Error-1012")) {
                message = i;
            }
        }
        org.junit.jupiter.api.Assertions.assertTrue(message >= 0, copy.toString());

        mvc.perform(get(url).param("message", Integer.toString(message)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.fixId").value("remove-empty-parameter"))
            .andExpect(jsonPath("$.revision").value(0))
            .andExpect(jsonPath("$.occurrences").value(1))
            .andExpect(jsonPath("$.changes[0].lineNumber").value(24))
            .andExpect(jsonPath("$.changes[0].after").doesNotExist())
            .andExpect(jsonPath("$.batch.revision").value(0))
            .andExpect(jsonPath("$.batch.operations[0].op").value("DeleteLine"));
        mvc.perform(get(BASE + "/" + id)).andExpect(jsonPath("$.revision").value(0))
            .andExpect(jsonPath("$.canUndo").value(false));
        mvc.perform(get(url).param("message", Integer.toString(message)).param("all", "true"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.occurrences").value(1));
        mvc.perform(get(url)).andExpect(status().isBadRequest());
        mvc.perform(get(url).param("message", "-1")).andExpect(status().isBadRequest());
        mvc.perform(get(url).param("message", "9999")).andExpect(status().isBadRequest());
        mvc.perform(get(url).param("message", "x")).andExpect(status().isBadRequest());
        mvc.perform(get(BASE + "/" + id + "/quick-fixes/no-such-fix").param("message", "0"))
            .andExpect(status().isBadRequest());
        mvc.perform(get(BASE + "/" + id + "/quick-fixes/reencode-latin1").param("message", "0"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("WINDOWS_1252")));
        mvc.perform(get(BASE + "/00000000-0000-0000-0000-000000000000/quick-fixes/normalise-doi")
            .param("message", "0")).andExpect(status().isNotFound());

        // apply it, then the preview of the old revision is stale
        String batch = json.readTree(mvc.perform(get(url).param("message", Integer.toString(message)))
            .andReturn().getResponse().getContentAsString()).get("batch").toString();
        mvc.perform(post(BASE + "/" + id + "/edits").contentType(MediaType.APPLICATION_JSON).content(batch))
            .andExpect(status().isOk());
        mvc.perform(post(BASE + "/" + id + "/edits").contentType(MediaType.APPLICATION_JSON).content(batch))
            .andExpect(status().isConflict());
        mvc.perform(get(url).param("message", Integer.toString(message))).andExpect(status().isConflict());
    }
}
