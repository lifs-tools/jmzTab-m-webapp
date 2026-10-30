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
package org.lifstools.mztab2.server.api.validation;

import org.junit.jupiter.api.extension.ExtendWith;
import org.lifstools.mztab.validator.webapp.AwaitAsyncValidations;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.lifstools.mztab2.model.ValidationLocation;
import org.lifstools.mztab2.model.ValidationMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lifstools.mztab.validator.webapp.Application;
import org.lifstools.mztab.validator.webapp.session.StorageFullException;
import org.lifstools.mztab.validator.webapp.session.ValidationSessionService;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import uk.ac.ebi.pride.utilities.ols.web.service.client.OLSClient;

@SpringBootTest(classes = Application.class, properties = {"eureka.client.enabled=false", "spring.cloud.discovery.enabled=false"})
@AutoConfigureMockMvc
@ExtendWith(AwaitAsyncValidations.class)
class ValidationsApiControllerTest {

    static final String UNKNOWN = "Unknown or expired validation.";

    @TempDir
    static Path storageRoot;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("storage.location", () -> storageRoot.toString());
    }

    @MockitoBean(answers = Answers.RETURNS_MOCKS)
    OLSClient olsClient;

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    static byte[] example() throws Exception {
        try (InputStream in = ValidationsApiControllerTest.class.getResourceAsStream("/metabolomics/lipidomics-example.mzTab")) {
            return in.readAllBytes();
        }
    }

    static MockMultipartFile file(String name) throws Exception {
        return new MockMultipartFile("file", name, "text/plain", example());
    }

    JsonNode create(String name) throws Exception {
        MvcResult r = mvc.perform(multipart("/rest/v2/validations").file(file(name)).param("level", "INFO"))
            .andExpect(status().isAccepted()).andReturn();
        return json.readTree(r.getResponse().getContentAsString());
    }

    JsonNode awaitFinished(String id) throws Exception {
        long end = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < end) {
            JsonNode n = json.readTree(mvc.perform(get("/rest/v2/validations/" + id)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
            if ("FINISHED".equals(n.get("status").asText()) || "FAILED".equals(n.get("status").asText())) {
                return n;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("validation did not finish");
    }

    @Test
    void createReturns202AndLocation() throws Exception {
        MvcResult r = mvc.perform(multipart("/rest/v2/validations").file(file("a.mzTab")))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.fileName").value("a.mzTab")).andReturn();
        String id = json.readTree(r.getResponse().getContentAsString()).get("id").asText();
        assertTrue(r.getResponse().getHeader("Location").endsWith("/rest/v2/validations/" + id));
        awaitFinished(id);
    }

    @Test
    void pollingReachesFinished() throws Exception {
        JsonNode created = create("poll.mzTab");
        assertEquals("FINISHED", awaitFinished(created.get("id").asText()).get("status").asText());
    }

    @Test
    void profileAndMappingTogetherIs400() throws Exception {
        mvc.perform(multipart("/rest/v2/validations").file(file("a.mzTab"))
            .file(new MockMultipartFile("mappingFile", "m.xml", "text/xml", "<x/>".getBytes()))
            .param("validationProfile", "mztab-m-2.0-default"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").exists());
    }

    @Test
    void maxErrorsOutOfRangeIs400() throws Exception {
        for (String v : new String[]{"0", "501"}) {
            mvc.perform(multipart("/rest/v2/validations").file(file("a.mzTab")).param("maxErrors", v))
                .andExpect(status().isBadRequest());
        }
    }

    @Test
    void hostileFileNameIsStoredSafely() throws Exception {
        for (String name : new String[]{"../../evil.mzTab", "..\\..\\evil.mzTab"}) {
            MvcResult r = mvc.perform(multipart("/rest/v2/validations").file(file(name)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.fileName").value("evil.mzTab")).andReturn();
            awaitFinished(json.readTree(r.getResponse().getContentAsString()).get("id").asText());
        }
        // a traversal would land one or two levels above the storage root
        assertTrue(!Files.exists(storageRoot.resolve("../evil.mzTab")));
        assertTrue(!Files.exists(storageRoot.resolve("../../evil.mzTab")));
        assertTrue(!Files.exists(storageRoot.resolve("../../../evil.mzTab")));
    }

    @Test
    void nonUuidIdIs404() throws Exception {
        mvc.perform(get("/rest/v2/validations/not-a-uuid"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.message").value(UNKNOWN));
    }

    @Test
    void deleteThenGetIs404() throws Exception {
        String id = create("del.mzTab").get("id").asText();
        awaitFinished(id);
        mvc.perform(delete("/rest/v2/validations/" + id)).andExpect(status().isNoContent());
        mvc.perform(get("/rest/v2/validations/" + id)).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.message").value(UNKNOWN));
        mvc.perform(delete("/rest/v2/validations/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void reportCsvHasHeaderAndRows() throws Exception {
        String id = create("rep.mzTab").get("id").asText();
        awaitFinished(id);
        MvcResult r = mvc.perform(get("/rest/v2/validations/" + id + "/report").param("format", "csv"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Disposition", "attachment; filename=\"rep.mzTab-validation.csv\""))
            .andReturn();
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals("level,line,category,code,message,suggestion,rule_id,section,row,column,metadata_key,metadata_group", body.lines().findFirst().orElseThrow());
        mvc.perform(get("/rest/v2/validations/" + id + "/report").param("format", "json"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Disposition", "attachment; filename=\"rep.mzTab-validation.json\""))
            .andExpect(jsonPath("$").isArray());
        mvc.perform(get("/rest/v2/validations/" + id + "/report").param("format", "xml"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void metadataEndpointsWork() throws Exception {
        String id = create("meta.mzTab").get("id").asText();
        awaitFinished(id);
        MvcResult r = mvc.perform(get("/rest/v2/validations/" + id + "/metadata")).andExpect(status().isOk())
            .andReturn();
        JsonNode groups = json.readTree(r.getResponse().getContentAsString());
        assertTrue(groups.size() > 0);
        String group = groups.get(0).get("name").asText();
        mvc.perform(get("/rest/v2/validations/" + id + "/metadata/" + group).param("size", "5"))
            .andExpect(status().isOk());
        mvc.perform(get("/rest/v2/validations/" + UUID.randomUUID() + "/metadata")).andExpect(status().isNotFound());
        mvc.perform(get("/rest/v2/validations/" + id + "/metadata/no-such-group"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void tablesEndpointPagesWithColumns() throws Exception {
        String id = create("tab.mzTab").get("id").asText();
        awaitFinished(id);
        mvc.perform(get("/rest/v2/validations/" + id + "/tables/sml").param("columns", "SML_ID").param("size", "5"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.columns[0]").value("SML_ID"))
            .andExpect(jsonPath("$.columns.length()").value(1))
            .andExpect(jsonPath("$.rows[0].cells.length()").value(1));
        mvc.perform(get("/rest/v2/validations/" + id + "/tables/sml").param("columns", "NOPE"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/rest/v2/validations/" + id + "/tables/sml").param("size", "201"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/rest/v2/validations/" + id + "/tables/xyz")).andExpect(status().isNotFound());
        mvc.perform(get("/rest/v2/validations/" + id + "/tables/sml").param("filter", "SML_ID:zzzzqqq"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalRows").value(0));
    }

    @Test
    void tooManyRequestedColumnsAre400() throws Exception {
        String id = create("cols.mzTab").get("id").asText();
        awaitFinished(id);
        String tooMany = String.join(",", java.util.Collections.nCopies(201, "SML_ID"));
        mvc.perform(get("/rest/v2/validations/" + id + "/tables/sml").param("columns", tooMany))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("At most 200 columns can be requested."));
    }

    @Test
    void absentTableIs404() throws Exception {
        mvc.perform(get("/rest/v2/validations/" + UUID.randomUUID() + "/tables/sml"))
            .andExpect(status().isNotFound());
        // a file that is not mzTab never gets section tables
        MvcResult r = mvc.perform(multipart("/rest/v2/validations")
            .file(new MockMultipartFile("file", "junk.txt", "text/plain", "not an mzTab file".getBytes())))
            .andExpect(status().isAccepted()).andReturn();
        String id = json.readTree(r.getResponse().getContentAsString()).get("id").asText();
        awaitFinished(id);
        mvc.perform(get("/rest/v2/validations/" + id + "/tables/sml")).andExpect(status().isNotFound());
    }

    @Test
    void csvNeutralisesFormulasAndQuotesPerRfc4180() {
        ValidationMessage formula = new ValidationMessage().messageType(ValidationMessage.MessageTypeEnum.ERROR)
            .category(ValidationMessage.CategoryEnum.FORMAT)
            .lineNumber(3L).code("1001").message("=HYPERLINK(\"http://evil\",\"x\")");
        ValidationMessage plain = new ValidationMessage().messageType(ValidationMessage.MessageTypeEnum.INFO)
            .lineNumber(4L).code("2").message("a, \"b\" c");
        ValidationMessage dash = new ValidationMessage().messageType(ValidationMessage.MessageTypeEnum.INFO)
            .message("-1+2");
        String[] lines = ValidationsApiController.csv(List.of(formula, plain, dash)).split("\r\n");
        assertEquals("level,line,category,code,message,suggestion,rule_id,section,row,column,metadata_key,metadata_group", lines[0]);
        // level and category are both upper-case enum names
        assertTrue(lines[1].startsWith("ERROR,3,FORMAT,1001,"), lines[1]);
        assertTrue(lines[2].startsWith("INFO,4,FORMAT,2,"), lines[2]);
        assertTrue(lines[1].endsWith(",\"'=HYPERLINK(\"\"http://evil\"\",\"\"x\"\")\",,,,,,,"), lines[1]);
        assertTrue(lines[2].endsWith(",\"a, \"\"b\"\" c\",,,,,,,"), lines[2]);
        assertTrue(lines[3].endsWith(",'-1+2,,,,,,,"), lines[3]);
    }

    @Test
    void csvHasLocationColumnsAndEscapesCode() {
        ValidationMessage m = new ValidationMessage().code("[Error-2009] line 5: \"x\", y")
            .category(ValidationMessage.CategoryEnum.LOGICAL).messageType(ValidationMessage.MessageTypeEnum.ERROR)
            .message("m").lineNumber(5L).suggestion("Add the column.").ruleId(null)
            .location(new ValidationLocation().section(ValidationLocation.SectionEnum.SML).rowIndex(1).column("reliability"));
        String[] lines = ValidationsApiController.csv(List.of(m)).split("\r\n");
        assertEquals("level,line,category,code,message,suggestion,rule_id,section,row,column,metadata_key,metadata_group", lines[0]);
        assertEquals("ERROR,5,LOGICAL,\"[Error-2009] line 5: \"\"x\"\", y\",m,Add the column.,,sml,2,reliability,,", lines[1]);
    }

    @Test
    void csvStripsTheTrailingLineBreakOfTheCode() {
        ValidationMessage m = new ValidationMessage().code("[Error-2009] line 5: \"x\", y\r\n").message("m");
        String csv = ValidationsApiController.csv(List.of(m));
        String[] lines = csv.split("\r\n");
        assertEquals(2, lines.length, csv);
        assertTrue(lines[1].contains(",\"[Error-2009] line 5: \"\"x\"\", y\",m,"), lines[1]);
    }

    @Test
    void csvWithoutLocationLeavesColumnsEmpty() {
        ValidationMessage m = new ValidationMessage().code("1001").message("m");
        String[] lines = ValidationsApiController.csv(List.of(m)).split("\r\n");
        assertTrue(lines[1].endsWith(",1001,m,,,,,,,"), lines[1]);
        assertEquals(12, lines[1].split(",", -1).length);
    }

    @Test
    void metadataPageAcceptsKey() throws Exception {
        String id = create("key.mzTab").get("id").asText();
        awaitFinished(id);
        String base = "/rest/v2/validations/" + id + "/metadata/assay";
        JsonNode all = json.readTree(mvc.perform(get(base).param("size", "200")).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString()).get("entries");
        String key = all.get(2).get("key").asText();
        mvc.perform(get(base).param("size", "1").param("key", key))
            .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(2))
            .andExpect(jsonPath("$.entries[0].key").value(key));
    }

    @Test
    void controlCharactersInFileNameAreStrippedAndHeaderIsWellFormed() throws Exception {
        MvcResult r = mvc.perform(multipart("/rest/v2/validations").file(file("a\r\nb\"c.mzTab")))
            .andExpect(status().isAccepted()).andReturn();
        JsonNode created = json.readTree(r.getResponse().getContentAsString());
        String name = created.get("fileName").asText();
        assertTrue(name.chars().noneMatch(Character::isISOControl), name);
        String id = created.get("id").asText();
        awaitFinished(id);
        String disposition = mvc.perform(get("/rest/v2/validations/" + id + "/report"))
            .andExpect(status().isOk()).andReturn().getResponse().getHeader("Content-Disposition");
        assertTrue(disposition.startsWith("attachment;"), disposition);
        assertTrue(disposition.chars().noneMatch(Character::isISOControl), disposition);
        assertTrue(disposition.indexOf("c.mzTab") > 0 || disposition.contains("%"), disposition);
    }

    @Test
    void tableFilterIsNotSplitAtCommasAndBracketColumnsWork() throws Exception {
        String id = create("flt.mzTab").get("id").asText();
        awaitFinished(id);
        String base = "/rest/v2/validations/" + id + "/tables/sml";
        mvc.perform(get(base).param("filter", "SML_ID:a,b:c")).andExpect(status().isOk())
            .andExpect(jsonPath("$.totalRows").value(0));
        JsonNode all = json.readTree(mvc.perform(get(base).param("size", "1")).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString()).get("allColumns");
        String bracket = null;
        for (JsonNode c : all) {
            if (c.asText().contains("[")) {
                bracket = c.asText();
                break;
            }
        }
        assertTrue(bracket != null, "example has a bracketed column");
        mvc.perform(get(base).param("columns", "SML_ID," + bracket).param("filter", bracket + ":"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.columns.length()").value(2));
    }

    @Test
    void malformedJsonToLegacyValidateStillIs400() throws Exception {
        mvc.perform(post("/rest/v2/validate").contentType("application/json").content("{not json"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void bindingErrorsDoNotLeakJavaTypes() throws Exception {
        MvcResult r = mvc.perform(multipart("/rest/v2/validations").file(file("a.mzTab")).param("maxErrors", "abc"))
            .andExpect(status().isBadRequest()).andReturn();
        String body = r.getResponse().getContentAsString();
        assertTrue(body.contains("Invalid value for parameter 'maxErrors'."), body);
        assertTrue(!body.contains("java.lang"), body);
    }

    @Test
    void mappingOver5MbIs400() throws Exception {
        byte[] big = new byte[5 * 1024 * 1024 + 1];
        java.util.Arrays.fill(big, (byte) ' ');
        mvc.perform(multipart("/rest/v2/validations").file(file("a.mzTab"))
            .file(new MockMultipartFile("mappingFile", "m.xml", "text/xml", big)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("The mapping file is larger than 5 MB."));
    }

    @Test
    void openApiListsNewEndpoints() throws Exception {
        mvc.perform(get("/v3/api-docs/mztab-m-api")).andExpect(status().isOk())
            .andExpect(jsonPath("$.paths['/rest/v2/validations']").exists())
            .andExpect(jsonPath("$.paths['/rest/v2/validations/{id}/tables/{section}']").exists());
    }
}
