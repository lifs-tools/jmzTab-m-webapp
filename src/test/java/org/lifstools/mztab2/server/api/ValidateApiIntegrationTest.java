package org.lifstools.mztab2.server.api;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.oneOf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lifstools.mztab.validator.webapp.Application;
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
