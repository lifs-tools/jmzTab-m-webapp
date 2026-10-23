package org.lifstools.mztab.validator.webapp;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.oneOf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.ac.ebi.pride.utilities.ols.web.service.client.OLSClient;

/**
 * Without the microservice profile, the validation UI and REST API must be
 * usable anonymously and must not redirect to a login page.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DefaultProfileAccessIntegrationTest {

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
    void startPageIsPublic() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk());
    }

    @Test
    void aboutPageIsPublic() throws Exception {
        mvc.perform(get("/about")).andExpect(status().isOk());
    }

    @Test
    void formUploadIsAcceptedAnonymously() throws Exception {
        mvc.perform(multipart("/")
                .file(mzTabFile("file"))
                .param("mzTabVersion", "MZTAB_2_0")
                .param("level", "INFO")
                .param("maxErrors", "100"))
            .andExpect(status().is3xxRedirection())
            .andExpect(header().string("Location", containsString("/validate/")));
    }

    @Test
    void restValidationIsAcceptedAnonymously() throws Exception {
        mvc.perform(post("/rest/v2/validatePlain")
                .contentType(MediaType.TEXT_PLAIN).accept(MediaType.APPLICATION_JSON)
                .content(mzTabFile("file").getBytes()))
            .andExpect(status().is(oneOf(200, 422)));
    }

    private MockMultipartFile mzTabFile(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/metabolomics/minimal-m-2.0.mztab")) {
            return new MockMultipartFile(name, "minimal-m-2.0.mztab", "text/plain", in.readAllBytes());
        }
    }
}
