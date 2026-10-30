package org.lifstools.mztab.validator.webapp;

import org.junit.jupiter.api.extension.ExtendWith;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lifstools.mztab.validator.webapp.service.validation.MappingFixtures;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.ac.ebi.pride.utilities.ols.web.service.client.OLSClient;

/**
 * End-to-end through the validations API: an uploaded mapping file that declares a DOCTYPE must be rejected, and
 * the bundled default validation profile must still work. OLS is mocked, so the test needs no network. Runs with the
 * production security setup (microservice profile), without Eureka.
 */
@SpringBootTest(properties = {"eureka.client.enabled=false", "spring.cloud.discovery.enabled=false"})
@ActiveProfiles("microservice")
@AutoConfigureMockMvc
@ExtendWith(AwaitAsyncValidations.class)
class MappingUploadIntegrationTest {

    @TempDir
    static Path storageRoot;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("storage.location", () -> storageRoot.toString());
        EngineTestSupport.register(registry);
    }

    @MockitoBean(answers = Answers.RETURNS_MOCKS)
    OLSClient olsClient;

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Test
    void uploadedMappingWithDoctypeIsRejected() throws Exception {
        MockMultipartFile mapping = new MockMultipartFile("mappingFile", "mapping.xml", "application/xml",
            MappingFixtures.MAPPING_WITH_DOCTYPE.getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart("/rest/v2/validations")
                .file(mzTabFile())
                .file(mapping)
                .param("level", "INFO")
                .param("maxErrors", "100"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.message", containsString("Mapping file rejected")));
    }

    @Test
    void defaultProfileWorks() throws Exception {
        String body = mvc.perform(multipart("/rest/v2/validations")
                .file(mzTabFile())
                .param("level", "INFO")
                .param("maxErrors", "100")
                .param("validationProfile", "mztab-m-2.0-default"))
            .andExpect(status().isAccepted())
            .andReturn().getResponse().getContentAsString();

        JsonNode done = awaitFinished(json.readTree(body).get("id").asText());

        assertEquals("FINISHED", done.get("status").asText(), "status: " + done);
        assertEquals("mztab-m-2.0-default", done.at("/validationProfile/id").asText(), "profile: " + done);
        assertFalse(done.get("messages").toString().contains("Mapping file rejected"),
            "default mapping must load, got: " + done.get("messages"));
        assertFalse(done.get("messages").toString().contains("Semantic validation failed"),
            "default mapping must load, got: " + done.get("messages"));
    }

    private JsonNode awaitFinished(String id) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(deadline)) {
            JsonNode n = json.readTree(mvc.perform(get("/rest/v2/validations/" + id))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            String status = n.get("status").asText();
            if ("FINISHED".equals(status) || "FAILED".equals(status)) {
                return n;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("validation did not finish within 30 s");
    }

    private MockMultipartFile mzTabFile() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/metabolomics/minimal-m-2.0.mztab")) {
            return new MockMultipartFile("file", "minimal-m-2.0.mztab", "text/plain", in.readAllBytes());
        }
    }
}
