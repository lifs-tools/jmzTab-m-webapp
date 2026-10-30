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
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lifstools.mztab.validator.webapp.service.validation.MappingFixtures;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end through the validations API: an uploaded mapping file that declares a DOCTYPE must be rejected, and
 * the bundled default validation profile must still work. Semantic validation in the engine asks the local OLS stub
 * of {@link EngineTestSupport}, so the test needs no network; the webapp's own OLS client is mocked. Runs with the
 * production security setup (microservice profile), without Eureka.
 */
@SpringBootTest(properties = {"eureka.client.enabled=false", "spring.cloud.discovery.enabled=false"})
@ActiveProfiles("microservice")
@AutoConfigureMockMvc
@ExtendWith(AwaitAsyncValidations.class)
class MappingUploadIntegrationTest {

    private static final Pattern NOT_ALLOWED
        = Pattern.compile("The cv term \"\\[[^,]*, ([^,]+),[^\"]*\" is not allowed for \"([^\"]+)\"");
    private static final Pattern EMPTY = Pattern.compile("accessed by (\\S+) is optional, but was null or empty");

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
        // as the engine judges minimal-m-2.0.mztab with the OLS stub's answers: three terms are
        // not below the terms their rules allow, and one optional element is empty
        List<String> crossChecks = new ArrayList<>();
        done.get("messages").forEach(m -> {
            if ("cross_check".equals(m.get("category").asText())) {
                crossChecks.add(m.get("message_type").asText() + " " + summary(m.get("message").asText()));
            }
        });
        assertEquals(List.of(
            "error MS:1002887 is not allowed for /metadata/@smallMoleculeFeatureQuantificationUnit",
            "error MS:1002887 is not allowed for /metadata/@smallMoleculeQuantificationUnit",
            "info MS:1000598 is not allowed for /metadata/instrument/@source",
            "info MS:1001530 is not allowed for /metadata/msRun/@idFormat",
            "info derivatization_agent_may is empty"), crossChecks.stream().sorted().toList(),
            "cross_check messages: " + done.get("messages"));
        assertEquals(List.of(), EngineTestSupport.ols().unmatched(), "every OLS request has a stub answer");
    }

    /** The term and path of a "not allowed" message, the rule of an "optional but empty" one. */
    private static String summary(String message) {
        Matcher notAllowed = NOT_ALLOWED.matcher(message);
        if (notAllowed.find()) {
            return notAllowed.group(1) + " is not allowed for " + notAllowed.group(2);
        }
        Matcher empty = EMPTY.matcher(message);
        return empty.find() ? empty.group(1) + " is empty" : message;
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
