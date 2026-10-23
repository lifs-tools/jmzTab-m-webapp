package org.lifstools.mztab.validator.webapp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lifstools.mztab.validator.webapp.domain.ToolResult;
import org.lifstools.mztab.validator.webapp.service.ToolResultService;
import org.lifstools.mztab.validator.webapp.service.ValidationService.Status;
import org.lifstools.mztab.validator.webapp.service.validation.MappingFixtures;
import org.lifstools.mztab2.model.ValidationMessage;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.ac.ebi.pride.utilities.ols.web.service.client.OLSClient;

/**
 * End-to-end through the Thymeleaf upload form: an uploaded mapping file that
 * declares a DOCTYPE must be rejected, and the bundled default mapping must
 * still work. OLS is mocked, so the test needs no network. Runs with the
 * production security setup (microservice profile), without Eureka.
 */
@SpringBootTest(properties = {"eureka.client.enabled=false", "spring.cloud.discovery.enabled=false"})
@ActiveProfiles("microservice")
@AutoConfigureMockMvc
class MappingUploadIntegrationTest {

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
    ToolResultService resultService;

    @Test
    void uploadedMappingWithDoctypeIsRejected() throws Exception {
        MockMultipartFile mapping = new MockMultipartFile("mappingFile", "mapping.xml", "application/xml",
            MappingFixtures.MAPPING_WITH_DOCTYPE.getBytes(StandardCharsets.UTF_8));

        List<ValidationMessage> messages = validateViaForm(mapping);

        assertTrue(messages.stream().anyMatch(m -> m.getMessage().contains("Mapping file rejected")),
            "expected a rejection message, got: " + messages);
    }

    @Test
    void defaultMappingIsUsedWhenNoneIsUploaded() throws Exception {
        MockMultipartFile noMapping = new MockMultipartFile("mappingFile", "", "application/xml", new byte[0]);

        List<ValidationMessage> messages = validateViaForm(noMapping);

        assertFalse(messages.stream().anyMatch(m -> m.getMessage().contains("Mapping file rejected")),
            "default mapping must load, got: " + messages);
    }

    private List<ValidationMessage> validateViaForm(MockMultipartFile mapping) throws Exception {
        MockHttpSession session = new MockHttpSession();
        String validateUrl = mvc.perform(multipart("/")
                .file(mzTabFile())
                .file(mapping)
                .param("level", "INFO")
                .param("maxErrors", "100")
                .param("checkCvMapping", "true")
                .session(session))
            .andExpect(status().is3xxRedirection())
            .andReturn().getResponse().getRedirectedUrl();

        URI validate = URI.create(validateUrl);
        mvc.perform(get(validate.getPath() + "?" + validate.getRawQuery()).session(session))
            .andExpect(status().is3xxRedirection());

        UUID sessionId = UUID.fromString(validate.getPath().substring(validate.getPath().lastIndexOf('/') + 1));
        return awaitMessages(sessionId);
    }

    private List<ValidationMessage> awaitMessages(UUID sessionId) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(deadline)) {
            ToolResult result = resultService.getOrCreateResultFor(sessionId);
            if (result.getStatus() == Status.FINISHED || result.getStatus() == Status.FAILED) {
                return result.getMessages();
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
