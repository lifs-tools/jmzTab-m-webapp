package org.lifstools.mztab.validator.webapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.ac.ebi.pride.utilities.ols.web.service.client.OLSClient;

/**
 * Every script, stylesheet and image a page references from this application
 * must be served. A webjar version bump once left the pages pointing at a
 * jQuery path that no longer existed; the 404 came back as JSON, the browser
 * blocked it (nosniff) and every script that needs jQuery failed.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PageAssetsIntegrationTest {

    private static final Pattern LOCAL_ASSET = Pattern.compile("(?:src|href)=\"(/[^\"/][^\"]*)\"");

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

    @ParameterizedTest
    @ValueSource(strings = {"/", "/about"})
    void everyReferencedAssetIsServed(String page) throws Exception {
        String html = mvc.perform(get(page)).andReturn().getResponse().getContentAsString();
        Set<String> assets = localAssets(html);
        assertThat(assets).as("assets referenced by %s", page)
            .anyMatch(a -> a.contains("/webjars/jquery/"))
            .anyMatch(a -> a.contains("/webjars/bootstrap/"));

        List<String> broken = new ArrayList<>();
        for (String asset : assets) {
            MockHttpServletResponse response = mvc.perform(get(asset)).andReturn().getResponse();
            String type = String.valueOf(response.getContentType());
            if (response.getStatus() != 200 || type.contains("json")) {
                broken.add(asset + " -> " + response.getStatus() + " " + type);
            }
        }
        assertThat(broken).as("assets referenced by %s that are not served", page).isEmpty();
    }

    private static Set<String> localAssets(String html) {
        Set<String> assets = new LinkedHashSet<>();
        Matcher m = LOCAL_ASSET.matcher(html);
        while (m.find()) {
            String path = m.group(1);
            if (path.matches(".*\\.(js|css|png|jpg|svg|ico|woff2?)(\\?.*)?$")) {
                assets.add(path);
            }
        }
        return assets;
    }
}
