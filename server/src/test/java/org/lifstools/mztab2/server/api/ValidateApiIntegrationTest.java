package org.lifstools.mztab2.server.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.oneOf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lifstools.mztab.validator.webapp.Application;
import org.lifstools.mztab2.io.MzTabFileParser;
import org.lifstools.mztab2.model.MzTab;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.ac.ebi.pride.jmztab2.utils.errors.MZTabErrorType;
import uk.ac.ebi.pride.utilities.ols.web.service.client.OLSClient;

/**
 * Characterises the public validation REST API, so that dependency upgrades
 * cannot silently change it. Used by lipidcompass-submissions
 * (validatePlain, semanticValidation=false, maxErrors=50).
 */
@SpringBootTest(classes = Application.class, properties = {"eureka.client.enabled=false", "spring.cloud.discovery.enabled=false"})
@ActiveProfiles("microservice")
@AutoConfigureMockMvc
class ValidateApiIntegrationTest {

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

    @Test
    void validatePlainReturnsAMessageList() throws Exception {
        mvc.perform(post("/rest/v2/validatePlain")
                .param("level", "info").param("maxErrors", "50")
                .contentType(MediaType.TEXT_PLAIN).accept(MediaType.APPLICATION_JSON)
                .content(resource("/metabolomics/minimal-m-2.0.mztab")))
            .andExpect(status().is(oneOf(200, 422)))
            .andExpect(jsonPath("$").isArray());
    }

    @Test
    void semanticValidationLoadsTheBundledMapping() throws Exception {
        mvc.perform(post("/rest/v2/validatePlain")
                .param("semanticValidation", "true")
                .contentType(MediaType.TEXT_PLAIN).accept(MediaType.APPLICATION_JSON)
                .content(resource("/metabolomics/minimal-m-2.0.mztab")))
            .andExpect(status().is(oneOf(200, 422)))
            .andExpect(jsonPath("$[*].message", everyItem(not(containsString("Mapping file rejected")))));
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

    @Test
    void validateAcceptsJson() throws Exception {
        mvc.perform(post("/rest/v2/validate")
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(model())))
            .andExpect(status().is(oneOf(200, 422)))
            .andExpect(jsonPath("$").isArray());
    }

    /** XML was listed, but the model written as XML was never read (400). */
    @Test
    void validateDoesNotTakeXml() throws Exception {
        mvc.perform(post("/rest/v2/validate")
                .contentType(MediaType.APPLICATION_XML).accept(MediaType.APPLICATION_JSON)
                .content(new XmlMapper().writeValueAsBytes(model())))
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

    private MzTab model() throws Exception {
        MzTabFileParser parser = new MzTabFileParser(Files.write(storageRoot.resolve("model.mztab"),
            resource("/metabolomics/minimal-m-2.0.mztab")).toFile());
        parser.parse(System.out, MZTabErrorType.Level.Error, 100);
        return parser.getMZTabFile();
    }

    @Test
    void openApiDocumentListsTheValidationEndpoints() throws Exception {
        mvc.perform(get("/v3/api-docs/mztab-m-api"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.paths['/rest/v2/validate']").exists())
            .andExpect(jsonPath("$.paths['/rest/v2/validatePlain']").exists());
    }

    private byte[] resource(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(name)) {
            return in.readAllBytes();
        }
    }
}
