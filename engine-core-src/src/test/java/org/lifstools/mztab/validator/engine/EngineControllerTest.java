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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.lifstools.mztab.validator.engine.api.MzTabVersions;
import org.lifstools.mztab.validator.engine.api.ValidationEvent;
import org.lifstools.mztab.validator.engine.api.ValidationMessage;
import org.lifstools.mztab2.io.MzTabFileParser;
import org.lifstools.mztab2.model.MzTab;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import uk.ac.ebi.pride.jmztab2.utils.errors.MZTabErrorType;

/**
 * The engine's HTTP contract, run unchanged against every engine.
 */
class EngineControllerTest extends EngineContextTest {

    private static final String VALIDATIONS = "/engine/v1/validations";
    private static final String TSV_TO_JSON = "/engine/v1/conversions/tsv-to-json";
    private static final String JSON_TO_TSV = "/engine/v1/conversions/json-to-tsv";

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JobLimiter limiter;

    @TempDir
    Path dir;

    @Test
    void infoReportsLineAndVersions() throws Exception {
        String line = System.getProperty("engine.line");
        assertThat(line).as("surefire sets engine.line").isNotBlank();

        JsonNode info = json(mvc.perform(get("/engine/v1/info"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andReturn());

        assertThat(info.get("line").asText()).isEqualTo(line);
        assertThat(info.get("mzTabVersions")).isNotEmpty();
        info.get("mzTabVersions").forEach(v -> assertThat(MzTabVersions.lineOf(v.asText())).contains(line));
        assertThat(info.get("jmztabmVersion").asText()).isNotBlank().doesNotContain("@");
        assertThat(info.get("build").asText()).isNotBlank();
        assertThat(info.get("maxUploadBytes").asLong()).isEqualTo(64L * 1024 * 1024);
        assertThat(info.get("maxConcurrentJobs").asInt()).isEqualTo(1);
    }

    @Test
    void validatesTsvAndStreamsOutcomeSectionsEnd() throws Exception {
        MvcResult result = mvc.perform(validation(file("lipidomics-example.mzTab", ofThisLine(Fixtures.LIPIDOMICS))))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("application/x-ndjson"))
            .andReturn();
        List<ValidationEvent> events = events(result);

        ValidationEvent.Outcome outcome = (ValidationEvent.Outcome) events.get(0);
        assertThat(outcome.parsed()).as("messages: %s", outcome.messages()).isTrue();
        assertThat(outcome.mzTabVersion()).isEqualTo(mzTabVersions[0]);
        assertThat(events.get(1)).isInstanceOf(ValidationEvent.MetadataLine.class);
        List<ValidationEvent.MetadataLine> metadata = ofType(events, ValidationEvent.MetadataLine.class);
        assertThat(metadata).isNotEmpty();
        assertThat(metadata.get(0).line()).isEqualTo(1);
        ValidationEvent.TableHeader sml = ofType(events, ValidationEvent.TableHeader.class).stream()
            .filter(t -> "SML".equals(t.section())).findFirst().orElseThrow();
        assertThat(sml.header().get(0)).isEqualTo("SML_ID");
        // the table event comes before its rows, and every row has the header's width
        int smlAt = events.indexOf(sml);
        List<ValidationEvent.TableRowCells> smlRows = ofType(events, ValidationEvent.TableRowCells.class).stream()
            .filter(r -> "SML".equals(r.section())).toList();
        assertThat(smlRows).isNotEmpty().allSatisfy(r -> {
            assertThat(events.indexOf(r)).isGreaterThan(smlAt);
            assertThat(r.cells()).hasSameSizeAs(sml.header());
        });
        ValidationEvent.End end = (ValidationEvent.End) events.get(events.size() - 1);
        assertThat(end.sections()).isNotNull();
        assertThat(end.sections().metadataLines()).isEqualTo(metadata.size());
        assertThat(end.sections().sml().rows()).isEqualTo(smlRows.size());
        assertThat(end.sections().sml().columns()).isEqualTo(sml.header().size());
        assertThat(ofType(events, ValidationEvent.Outcome.class)).hasSize(1);
        assertThat(ofType(events, ValidationEvent.End.class)).hasSize(1);
    }

    @Test
    void validatesJsonUpload() throws Exception {
        MzTab model = model(ofThisLine(Fixtures.LIPIDOMICS));

        List<ValidationEvent> events = events(mvc.perform(validation(file("lipidomics-example.mzTab.json",
            objectMapper.writeValueAsBytes(model))))
            .andExpect(status().isOk())
            .andReturn());

        ValidationEvent.Outcome outcome = (ValidationEvent.Outcome) events.get(0);
        assertThat(outcome.parsed()).as("messages: %s", outcome.messages()).isTrue();
        assertThat(outcome.mzTabVersion()).isEqualTo(mzTabVersions[0]);
        assertThat(((ValidationEvent.End) events.get(events.size() - 1)).sections().sml()).isNotNull();
    }

    @Test
    void validatesJsonWithoutVersionReportsError2008() throws Exception {
        MzTab model = model(ofThisLine(Fixtures.MINIMAL));
        model.getMetadata().setMzTabVersion(null);

        ValidationEvent.Outcome outcome = outcome(validation(file("no-version.json",
            objectMapper.writeValueAsBytes(model))));

        assertThat(outcome.mzTabVersion()).as("the file declares no version").isNull();
        assertThat(outcome.messages()).extracting(ValidationMessage::getCode)
            .anySatisfy(code -> assertThat(code).contains("2008"));
    }

    @Test
    void malformedJsonUploadIsNotParsed() throws Exception {
        List<ValidationEvent> events = events(mvc.perform(validation(file("broken.json",
            "{\"metadata\": {".getBytes(StandardCharsets.UTF_8))))
            .andExpect(status().isOk())
            .andReturn());

        ValidationEvent.Outcome outcome = (ValidationEvent.Outcome) events.get(0);
        assertThat(outcome.parsed()).isFalse();
        assertThat(outcome.messages()).singleElement()
            .satisfies(m -> assertThat(m.getMessage()).startsWith(EngineController.JSON_NOT_READ));
        assertThat(events).hasSize(2);
    }

    @Test
    void unparseableFileStreamsOutcomeAndEndOnly() throws Exception {
        List<ValidationEvent> events = events(mvc.perform(validation(file("garbage.mztab",
            "garbage".getBytes(StandardCharsets.UTF_8))))
            .andExpect(status().isOk())
            .andReturn());

        assertThat(events).hasSize(2);
        ValidationEvent.Outcome outcome = (ValidationEvent.Outcome) events.get(0);
        assertThat(outcome.parsed()).isFalse();
        assertThat(outcome.messages()).isNotEmpty();
        assertThat(((ValidationEvent.End) events.get(1)).sections()).isNull();
    }

    @Test
    void nonUtf8FileIsReportedByTheParserNotRejected() throws Exception {
        // detectTsv cannot decode the file; the engine leaves it to jmzTab-m instead of failing
        byte[] latin1 = ("MTD\tmzTab-version\t" + mzTabVersions[0] + "\nCOM\tM\u00fcller\n")
            .getBytes(StandardCharsets.ISO_8859_1);

        List<ValidationEvent> events = events(mvc.perform(validation(file("latin1.mztab", latin1)))
            .andExpect(status().isOk())
            .andReturn());

        assertThat(events.get(0)).isInstanceOf(ValidationEvent.Outcome.class);
        assertThat(events.get(events.size() - 1)).isInstanceOf(ValidationEvent.End.class);
    }

    @Test
    void semanticValidationRunsOnlyWithMapping() throws Exception {
        byte[] lipidomics = ofThisLine(Fixtures.LIPIDOMICS);

        ValidationEvent.Outcome with = outcome(validation(file("lipidomics-example.mzTab", lipidomics))
            .file(mapping(Fixtures.resource(Fixtures.BUNDLED_MAPPING))));
        ValidationEvent.Outcome without = outcome(validation(file("lipidomics-example.mzTab", lipidomics)));

        assertThat(with.messages()).as("semantic messages").anySatisfy(m -> assertThat(m.getRuleId()).isNotNull());
        assertThat(without.messages()).allSatisfy(m -> assertThat(m.getRuleId()).isNull());
    }

    @Test
    void mappingWithDoctypeIsRejected() throws Exception {
        ValidationEvent.Outcome outcome = outcome(validation(file("minimal.mztab", ofThisLine(Fixtures.MINIMAL)))
            .file(mapping(MappingFixtures.MAPPING_WITH_DOCTYPE.getBytes(StandardCharsets.UTF_8))));

        assertThat(outcome.messages()).extracting(ValidationMessage::getMessage)
            .anySatisfy(m -> assertThat(m).contains("Mapping file rejected"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"level=DEBUG", "maxErrors=-1", "maxErrors=501", "maxErrors=many"})
    void rejectsInvalidParameters(String query) throws Exception {
        String[] parameter = query.split("=", 2);
        mvc.perform(validation(file("minimal.mztab", ofThisLine(Fixtures.MINIMAL)))
            .param(parameter[0], parameter[1]))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value("invalid_request"))
            .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void invalidParameterMessageIsEnglishWhateverTheClientsLanguage() throws Exception {
        mvc.perform(validation(file("minimal.mztab", ofThisLine(Fixtures.MINIMAL)))
            .param("maxErrors", "501").header("Accept-Language", "de-DE"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(
                "maxErrors: must be less than or equal to 500")));
    }

    @Test
    void rejectsRequestWithoutFile() throws Exception {
        mvc.perform(multipart(VALIDATIONS).file(mapping(MappingFixtures.MINIMAL_MAPPING.getBytes(StandardCharsets.UTF_8))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("invalid_request"));
    }

    @Test
    void acceptsBoundaryParameters() throws Exception {
        mvc.perform(validation(file("minimal.mztab", ofThisLine(Fixtures.MINIMAL)))
            .param("level", "ERROR").param("maxErrors", "0"))
            .andExpect(status().isOk());
        mvc.perform(validation(file("minimal.mztab", ofThisLine(Fixtures.MINIMAL)))
            .param("level", "WARN").param("maxErrors", "500"))
            .andExpect(status().isOk());
    }

    @Test
    void rejectsFileOfOtherLine() throws Exception {
        String other = Fixtures.otherLineVersion(System.getProperty("engine.line"));
        byte[] file = Fixtures.withVersion(Fixtures.resource(Fixtures.MINIMAL), other);

        mvc.perform(validation(file("other.mztab", file)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("unsupported_version"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(other)));
    }

    @Test
    void rejectsJsonOfOtherLine() throws Exception {
        String other = Fixtures.otherLineVersion(System.getProperty("engine.line"));
        byte[] json = ("{\"metadata\": {\"mzTab-version\": \"" + other + "\"}}").getBytes(StandardCharsets.UTF_8);

        mvc.perform(validation(file("other.json", json)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("unsupported_version"));
    }

    @Test
    void acceptsFileWithoutVersion() throws Exception {
        byte[] file = Fixtures.withoutVersion(Fixtures.resource(Fixtures.MINIMAL));

        ValidationEvent.Outcome outcome = outcome(validation(file("no-version.mztab", file)));

        assertThat(outcome.messages()).extracting(ValidationMessage::getCode)
            .anySatisfy(code -> assertThat(code).contains("2008"));
    }

    @Test
    void busyWhenAllSlotsTaken() throws Exception {
        try (JobLimiter.Permit held = limiter.tryAcquire().orElseThrow()) {
            mvc.perform(validation(file("minimal.mztab", ofThisLine(Fixtures.MINIMAL))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.code").value("busy"));
            mvc.perform(post(TSV_TO_JSON).content(ofThisLine(Fixtures.MINIMAL)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.code").value("busy"));
        }
        // the slot of a finished request is free again
        mvc.perform(validation(file("minimal.mztab", ofThisLine(Fixtures.MINIMAL))))
            .andExpect(status().isOk());
        mvc.perform(validation(file("minimal.mztab", ofThisLine(Fixtures.MINIMAL))))
            .andExpect(status().isOk());
    }

    @Test
    void oversizedMappingIs413() throws Exception {
        byte[] huge = (MappingFixtures.MINIMAL_MAPPING + " ".repeat(5 * 1024 * 1024))
            .getBytes(StandardCharsets.UTF_8);

        mvc.perform(validation(file("minimal.mztab", ofThisLine(Fixtures.MINIMAL))).file(mapping(huge)))
            .andExpect(status().isPayloadTooLarge())
            .andExpect(jsonPath("$.code").value("file_too_large"));
    }

    @Test
    void convertsTsvToJsonAndBack() throws Exception {
        String json = mvc.perform(post(TSV_TO_JSON).contentType("text/tab-separated-values")
            .content(ofThisLine(Fixtures.LIPIDOMICS)))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.metadata['mzTab-version']").value(mzTabVersions[0]))
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        String tsv = mvc.perform(post(JSON_TO_TSV).contentType(MediaType.APPLICATION_JSON).content(json))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("text/tab-separated-values"))
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(tsv).startsWith("MTD\tmzTab-version");
    }

    @Test
    void tsvToJsonReportsErrorsWith422() throws Exception {
        String body = mvc.perform(post(TSV_TO_JSON).content("garbage".getBytes(StandardCharsets.UTF_8)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        ValidationMessage[] messages = objectMapper.readValue(body, ValidationMessage[].class);
        assertThat(messages).isNotEmpty()
            .allSatisfy(m -> assertThat(m.getMessageType()).isEqualTo(ValidationMessage.MessageType.ERROR));
    }

    @Test
    void jsonToTsvReportsUnwritableModelWith422() throws Exception {
        // a model without metadata: jmzTab-m's writer refuses it
        mvc.perform(post(JSON_TO_TSV).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$[0].message").value(org.hamcrest.Matchers.startsWith(EngineConverter.NOT_WRITTEN)));
    }

    @Test
    void jsonToTsvOfModelWithoutVersionWritesNoVersion() throws Exception {
        MzTab model = model(ofThisLine(Fixtures.MINIMAL));
        model.getMetadata().setMzTabVersion(null);
        byte[] json = objectMapper.writeValueAsBytes(model);

        String tsv = mvc.perform(post(JSON_TO_TSV).param("validate", "false")
            .contentType(MediaType.APPLICATION_JSON).content(json))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(tsv).startsWith("MTD\t").doesNotContain("mzTab-version");

        String body = mvc.perform(post(JSON_TO_TSV).contentType(MediaType.APPLICATION_JSON).content(json))
            .andExpect(status().isUnprocessableEntity())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(objectMapper.readValue(body, ValidationMessage[].class)).extracting(ValidationMessage::getCode)
            .anySatisfy(code -> assertThat(code).contains("2008"));
    }

    @Test
    void jsonToTsvRejectsNonJson() throws Exception {
        mvc.perform(post(JSON_TO_TSV).contentType(MediaType.APPLICATION_JSON).content("not json"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("not_json"));
    }

    @Test
    void conversionsRejectFilesOfOtherLine() throws Exception {
        String other = Fixtures.otherLineVersion(System.getProperty("engine.line"));

        mvc.perform(post(TSV_TO_JSON).content(Fixtures.withVersion(Fixtures.resource(Fixtures.MINIMAL), other)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("unsupported_version"));
        mvc.perform(post(JSON_TO_TSV).contentType(MediaType.APPLICATION_JSON)
            .content("{\"metadata\": {\"mzTab-version\": \"" + other + "\"}}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("unsupported_version"));
    }

    /** The upload limit, lowered for this context (MockMvc bypasses the servlet container's own check). */
    @Nested
    @TestPropertySource(properties = "spring.servlet.multipart.max-file-size=1KB")
    class UploadLimit {

        /** The MockMvc of this context; the enclosing instance's field belongs to the other context. */
        @Autowired
        MockMvc mvc;

        @Test
        void oversizedUploadIs413() throws Exception {
            mvc.perform(validation(file("lipidomics-example.mzTab", ofThisLine(Fixtures.LIPIDOMICS))))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("file_too_large"));
        }

        @Test
        void oversizedConversionBodyIs413() throws Exception {
            mvc.perform(post(TSV_TO_JSON).content(ofThisLine(Fixtures.LIPIDOMICS)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("file_too_large"));
        }
    }

    private static MockMultipartHttpServletRequestBuilder validation(MockMultipartFile file) {
        return (MockMultipartHttpServletRequestBuilder) multipart(VALIDATIONS).file(file);
    }

    private static MockMultipartFile file(String name, byte[] content) {
        return new MockMultipartFile("file", name, MediaType.APPLICATION_OCTET_STREAM_VALUE, content);
    }

    private static MockMultipartFile mapping(byte[] content) {
        return new MockMultipartFile("mapping", "mapping.xml", MediaType.APPLICATION_XML_VALUE, content);
    }

    private ValidationEvent.Outcome outcome(MockMultipartHttpServletRequestBuilder request) throws Exception {
        List<ValidationEvent> events = events(mvc.perform(request).andExpect(status().isOk()).andReturn());
        return (ValidationEvent.Outcome) events.get(0);
    }

    private List<ValidationEvent> events(MvcResult result) throws Exception {
        List<ValidationEvent> events = new ArrayList<>();
        for (String line : result.getResponse().getContentAsString(StandardCharsets.UTF_8).split("\n")) {
            if (!line.isEmpty()) {
                events.add(objectMapper.readValue(line, ValidationEvent.class));
            }
        }
        return events;
    }

    /** The model of a tab separated fixture, as jmzTab-m parses it. */
    private MzTab model(byte[] tsv) throws Exception {
        Path file = Files.write(dir.resolve("model.mztab"), tsv);
        MzTabFileParser parser = new MzTabFileParser(file.toFile());
        parser.parse(System.out, MZTabErrorType.Level.Error, 100);
        assertThat(parser.getMZTabFile()).as("errors: %s", parser.getErrorList()).isNotNull();
        return parser.getMZTabFile();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static <T> List<T> ofType(List<ValidationEvent> events, Class<T> type) {
        return events.stream().filter(type::isInstance).map(type::cast).toList();
    }
}
