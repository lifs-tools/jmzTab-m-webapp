package org.lifstools.mztab2.server.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.oneOf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.lifstools.mztab.validator.webapp.Application;
import org.lifstools.mztab.validator.webapp.EngineProxy;
import org.lifstools.mztab.validator.webapp.VersionFixtures;
import org.lifstools.mztab.validator.webapp.service.validation.SynchronousLimiter;
import org.lifstools.mztab.validator.webapp.validationprofile.ValidationProfile;
import org.lifstools.mztab.validator.webapp.validationprofile.ValidationProfileCatalog;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Characterises the public validation REST API, so that dependency upgrades
 * cannot silently change it. Used by lipidcompass-submissions
 * (validatePlain, semanticValidation=false, maxErrors=50). The webapp's only
 * engine is the real mzTab-M 2.0 engine, behind an {@link EngineProxy} that
 * records what the webapp sends it.
 */
@SpringBootTest(classes = Application.class, properties = {"eureka.client.enabled=false", "spring.cloud.discovery.enabled=false"})
@ActiveProfiles("microservice")
@AutoConfigureMockMvc
class ValidateApiIntegrationTest {

    static final MediaType TSV = MediaType.parseMediaType("text/tab-separated-values");

    static final EngineProxy ENGINE = EngineProxy.start();


    @TempDir
    static Path storageRoot;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("storage.location", () -> storageRoot.toString());
        ENGINE.register(registry);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    ValidationProfileCatalog catalog;

    @AfterEach
    void forwardAgain() {
        ENGINE.reset();
    }

    @Test
    void validatePlainReturnsAMessageList() throws Exception {
        mvc.perform(post("/rest/v2/validatePlain")
                .param("level", "info").param("maxErrors", "50")
                .contentType(MediaType.TEXT_PLAIN).accept(MediaType.APPLICATION_JSON)
                .content(resource("/metabolomics/minimal-m-2.0.mztab")))
            .andExpect(status().is(oneOf(200, 422)))
            .andExpect(jsonPath("$").isArray());
    }

    /** The fields a legacy message had before the editor (493aa7d); {@code cause} is not among them. */
    static final java.util.Set<String> LEGACY_FIELDS = java.util.Set.of("code", "category", "message_type",
        "message", "line_number", "suggestion", "rule_id", "location");

    /** Every message of a legacy response has only fields of the pre-editor set, and none has a cause. */
    static void assertLegacyFields(com.fasterxml.jackson.databind.JsonNode messages) {
        assertThat(messages.isArray()).isTrue();
        assertThat(messages.size()).isPositive();
        for (com.fasterxml.jackson.databind.JsonNode m : messages) {
            java.util.List<String> names = new java.util.ArrayList<>();
            m.fieldNames().forEachRemaining(names::add);
            assertThat(names).as(m.toString()).isSubsetOf(LEGACY_FIELDS);
        }
    }

    @Test
    void legacyMessagesKeepThePreEditorFields() throws Exception {
        // an empty parameter: Error-1012, a code jmzTab-m has a cause for
        byte[] file = new String(resource("/metabolomics/lipidomics-example.mzTab"), StandardCharsets.UTF_8)
            .replace("[MS, MS:1000624, inductive detector, ]", "[, , , ]").getBytes(StandardCharsets.UTF_8);
        String body = mvc.perform(post("/rest/v2/validatePlain")
                .contentType(MediaType.TEXT_PLAIN).accept(MediaType.APPLICATION_JSON).content(file))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$[*].code", hasItem(containsString("1012"))))
            .andReturn().getResponse().getContentAsString();
        assertLegacyFields(json.readTree(body));
    }

    @Test
    void structuralValidationSendsNoMapping() throws Exception {
        mvc.perform(post("/rest/v2/validatePlain")
                .contentType(MediaType.TEXT_PLAIN).accept(MediaType.APPLICATION_JSON)
                .content(resource("/metabolomics/minimal-m-2.0.mztab")))
            .andExpect(status().is(oneOf(200, 422)));

        assertThat(validationJob().part("file")).isPresent();
        assertThat(validationJob().part("mapping")).isEmpty();
    }

    @Test
    void semanticValidationMappingIsAccepted() throws Exception {
        mvc.perform(post("/rest/v2/validatePlain")
                .param("semanticValidation", "true")
                .contentType(MediaType.TEXT_PLAIN).accept(MediaType.APPLICATION_JSON)
                .content(resource("/metabolomics/minimal-m-2.0.mztab")))
            .andExpect(status().is(oneOf(200, 422)))
            .andExpect(jsonPath("$[*].message", everyItem(not(containsString("Mapping file rejected")))));
    }

    /**
     * Review focus 5: the rules are the default profile of the file's own
     * line; a line without an engine on this instance gets the unsupported
     * message, and nothing reaches an engine.
     */
    @Test
    void semanticValidationUsesTheDefaultProfileOfTheFilesLine() throws Exception {
        mvc.perform(post("/rest/v2/validatePlain")
                .param("semanticValidation", "true")
                .contentType(MediaType.TEXT_PLAIN).accept(MediaType.APPLICATION_JSON)
                .content(withVersion(resource("/metabolomics/minimal-m-2.0.mztab"), "2.1.0-M")))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].message_type").value("error"))
            .andExpect(jsonPath("$[0].message")
                .value("This file declares mzTab-version 2.1.0-M. This validator supports mzTab-M 2.0."));
        assertThat(ENGINE.jobs()).isEmpty();

        mvc.perform(post("/rest/v2/validatePlain")
                .param("semanticValidation", "true")
                .contentType(MediaType.TEXT_PLAIN).accept(MediaType.APPLICATION_JSON)
                .content(resource("/metabolomics/minimal-m-2.0.mztab")))
            .andExpect(status().is(oneOf(200, 422)));
        ValidationProfile latest = catalog.find("mztab-m-2.0-default", null).orElseThrow();
        byte[] expected;
        try (InputStream in = catalog.mappingUrl(latest).openStream()) {
            expected = in.readAllBytes();
        }
        assertThat(validationJob().part("mapping")).hasValueSatisfying(m -> assertThat(m).isEqualTo(expected));
    }

    @Test
    void validateRoutesByTheJsonVersion() throws Exception {
        mvc.perform(post("/rest/v2/validate")
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                .content(ConvertApiIntegrationTest.METADATA_ONLY.replace("2.0.0-M", "2.1.0-M")))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].message")
                .value("This file declares mzTab-version 2.1.0-M. This validator supports mzTab-M 2.0."));
        assertThat(ENGINE.jobs()).isEmpty();
    }

    /** F1: one ISO-8859-1 byte after the version line must not send a 2.1 file to the 2.0 engine. */
    @Test
    void nonUtf8FileOfALineWithoutEngineGetsTheUnsupportedMessage() throws Exception {
        mvc.perform(post("/rest/v2/validatePlain")
                .contentType(MediaType.TEXT_PLAIN).accept(MediaType.APPLICATION_JSON)
                .content(VersionFixtures.LATIN1_21))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].message")
                .value("This file declares mzTab-version 2.1.0-M. This validator supports mzTab-M 2.0."));
        assertThat(ENGINE.jobs()).isEmpty();
    }

    @Test
    void mzTab1FileIsRejectedWithOneMessage() throws Exception {
        mvc.perform(post("/rest/v2/validatePlain")
                .contentType(MediaType.TEXT_PLAIN).accept(MediaType.APPLICATION_JSON)
                .content("MTD\tmzTab-version\t1.0.0\n"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].message").value("This file declares mzTab-version 1.0. mzTab 1.0 is no "
                + "longer supported; the validator checks mzTab-M 2.0 files."));
        assertThat(ENGINE.jobs()).isEmpty();
    }

    @Test
    void validateWithEngineDownIs503() throws Exception {
        ENGINE.mode(EngineProxy.Mode.DOWN);
        mvc.perform(post("/rest/v2/validatePlain")
                .contentType(MediaType.TEXT_PLAIN).accept(MediaType.APPLICATION_JSON)
                .content(resource("/metabolomics/minimal-m-2.0.mztab")))
            .andExpect(status().isServiceUnavailable())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.error").value("Service Unavailable"))
            .andExpect(jsonPath("$.message")
                .value("The mzTab-M 2.0 validator is temporarily unavailable. Please try again later."));
        mvc.perform(post("/rest/v2/validate")
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                .content(ConvertApiIntegrationTest.METADATA_ONLY))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.message")
                .value("The mzTab-M 2.0 validator is temporarily unavailable. Please try again later."));
    }

    @Test
    void validateWithEngineBusyIs503WithRetryAfter() throws Exception {
        ENGINE.mode(EngineProxy.Mode.BUSY);
        ENGINE.retryAfterSeconds(7);
        mvc.perform(post("/rest/v2/validatePlain")
                .contentType(MediaType.TEXT_PLAIN).accept(MediaType.APPLICATION_JSON)
                .content(resource("/metabolomics/minimal-m-2.0.mztab")))
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().string("Retry-After", "7"))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.message").value(SynchronousLimiter.BUSY));
    }

    @Test
    void brokenFileIsReportedNotFailed() throws Exception {
        mvc.perform(post("/rest/v2/validatePlain")
                .contentType(MediaType.TEXT_PLAIN).accept(MediaType.APPLICATION_JSON)
                .content("this is not an mzTab file"))
            .andExpect(status().is(oneOf(200, 422)))
            .andExpect(jsonPath("$").isArray());
    }

    /** It was 500: the unknown level reached ValidationLevel.valueOf. */
    @Test
    void unknownLevelIsABadRequest() throws Exception {
        mvc.perform(post("/rest/v2/validatePlain")
                .param("level", "verbose")
                .contentType(MediaType.TEXT_PLAIN).accept(MediaType.APPLICATION_JSON)
                .content(resource("/metabolomics/minimal-m-2.0.mztab")))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    /** Bean validation answered these with a ProblemDetail, not with the error body of the other endpoints. */
    @ParameterizedTest
    @CsvSource({"/rest/v2/validatePlain, 501", "/rest/v2/validatePlain, -1", "/rest/v2/validate, 501",
        "/rest/v2/validate, -1"})
    void maxErrorsOutOfRangeIsABadRequest(String path, String maxErrors) throws Exception {
        boolean plain = path.endsWith("Plain");
        mvc.perform(post(path).param("maxErrors", maxErrors)
                .contentType(plain ? MediaType.TEXT_PLAIN : MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(plain ? new String(resource("/metabolomics/minimal-m-2.0.mztab"), StandardCharsets.UTF_8)
                    : ConvertApiIntegrationTest.METADATA_ONLY))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.error").value("Bad Request"))
            .andExpect(jsonPath("$.message", containsString("maxErrors")))
            .andExpect(jsonPath("$.path").value(path))
            .andExpect(jsonPath("$.timestamp").exists())
            .andExpect(jsonPath("$.title").doesNotExist());
        assertThat(ENGINE.jobs()).isEmpty();
    }

    @Test
    void validateAcceptsJson() throws Exception {
        byte[] model = model();
        mvc.perform(post("/rest/v2/validate")
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                .content(model))
            .andExpect(status().is(oneOf(200, 422)))
            .andExpect(jsonPath("$").isArray());
        // the engine converts the JSON itself
        assertThat(validationJob().part("file")).hasValueSatisfying(f -> assertThat(f).isEqualTo(model));
    }

    /** XML was listed, but the model written as XML was never read (400). */
    @Test
    void validateDoesNotTakeXml() throws Exception {
        mvc.perform(post("/rest/v2/validate")
                .contentType(MediaType.APPLICATION_XML).accept(MediaType.APPLICATION_JSON)
                .content(new XmlMapper().writeValueAsBytes(json.readTree(model()))))
            .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void validateReportsUnreadableJsonAs400() throws Exception {
        mvc.perform(post("/rest/v2/validate")
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                .content("{\"metadata\": "))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.message", containsString("not mzTab-M JSON")));
    }

    /** As when the webapp read the model itself; the asynchronous upload reports it as a message instead. */
    @Test
    void validateReportsJsonThatIsNotAModelAs400() throws Exception {
        mvc.perform(post("/rest/v2/validate")
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                .content("{\"metadata\": 5}"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.error").value("Bad Request"))
            .andExpect(jsonPath("$.message", containsString("The request body is not mzTab-M JSON: ")))
            .andExpect(jsonPath("$.message", not(containsString("The JSON file could not be read"))));
    }

    /** It was System.nanoTime(), which is no point in time. */
    @Test
    void errorsCarryTheTimeTheyHappened() throws Exception {
        Instant before = Instant.now();
        String body = mvc.perform(post("/rest/v2/validate")
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                .content("{\"metadata\": "))
            .andExpect(status().isBadRequest())
            .andReturn().getResponse().getContentAsString();
        Instant after = Instant.now();

        Instant timestamp = Instant.parse(json.readTree(body).get("timestamp").asText());
        assertThat(timestamp).isBetween(before, after);
    }

    @Test
    void openApiDocumentListsTheValidationEndpoints() throws Exception {
        mvc.perform(get("/v3/api-docs/mztab-m-api"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.paths['/rest/v2/validate']").exists())
            .andExpect(jsonPath("$.paths['/rest/v2/validatePlain']").exists());
    }

    /** The JSON model of the minimal 2.0 file, as the engine converts it. */
    private byte[] model() throws Exception {
        return mvc.perform(post("/rest/v2/convertPlain")
                .contentType(TSV).accept(MediaType.APPLICATION_JSON)
                .content(resource("/metabolomics/minimal-m-2.0.mztab")))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsByteArray();
    }

    /** The last validation the webapp sent to the engine. */
    private static EngineProxy.Recorded validationJob() {
        List<EngineProxy.Recorded> validations = ENGINE.jobs().stream()
            .filter(j -> j.path().startsWith("/engine/v1/validations")).toList();
        assertThat(validations).as("validations sent to the engine").isNotEmpty();
        return validations.get(validations.size() - 1);
    }

    static byte[] withVersion(byte[] tsv, String version) {
        return new String(tsv, StandardCharsets.UTF_8)
            .replaceFirst("(?m)^MTD\\tmzTab-version\\t[^\\t\\r\\n]*", "MTD\tmzTab-version\t" + version)
            .getBytes(StandardCharsets.UTF_8);
    }

    private byte[] resource(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(name)) {
            return in.readAllBytes();
        }
    }
}
