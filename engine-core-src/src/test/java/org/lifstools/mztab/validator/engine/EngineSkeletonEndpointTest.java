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

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * {@code POST /engine/v1/skeletons}. Each engine answers for the version it serves and refuses the other line, so the
 * test asks for what this engine serves and for what it does not.
 */
class EngineSkeletonEndpointTest extends EngineContextTest {

    private static final String SKELETONS = "/engine/v1/skeletons";

    @Test
    void theSkeletonOfAServedVersionIsTabSeparatedText() throws Exception {
        if (serves("2.0.0-M")) {
            mvc.perform(post(SKELETONS).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"mzTabVersion\":\"2.0.0-M\",\"mzTabId\":\"my-study\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/tab-separated-values"))
                .andExpect(content().encoding("UTF-8"))
                .andExpect(content().string("MTD\tmzTab-version\t2.0.0-M\nMTD\tmzTab-ID\tmy-study\n"));
        } else {
            mvc.perform(post(SKELETONS).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"mzTabVersion\":\"2.1.0-M\",\"contentProfile\":\"M+S\",\"mzTabId\":\"my-study\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/tab-separated-values"))
                .andExpect(content().string(
                    "MTD\tmzTab-version\t2.1.0-M\nMTD\tmzTab-ID\tmy-study\nMTD\tmzTab-profile\tM+S\n"));
        }
    }

    @Test
    void aVersionThisEngineDoesNotServeIsInvalid() throws Exception {
        String other = serves("2.0.0-M") ? "{\"mzTabVersion\":\"2.1.0-M\",\"contentProfile\":\"M\",\"mzTabId\":\"a\"}"
            : "{\"mzTabVersion\":\"2.0.0-M\",\"mzTabId\":\"a\"}";
        mvc.perform(post(SKELETONS).contentType(MediaType.APPLICATION_JSON).content(other))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("invalid_request"))
            .andExpect(jsonPath("$.message").value(containsString("does not serve")));
    }

    @Test
    void anIdThatFailsThePatternIsInvalid() throws Exception {
        String version = mzTabVersions[0].strip();
        String profile = version.startsWith("2.0") ? "" : ",\"contentProfile\":\"M\"";
        mvc.perform(post(SKELETONS).contentType(MediaType.APPLICATION_JSON)
                .content("{\"mzTabVersion\":\"" + version + "\"" + profile + ",\"mzTabId\":\"-a\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("invalid_request"));
    }

    @Test
    void aBodyThatIsNotASkeletonRequestIsInvalid() throws Exception {
        mvc.perform(post(SKELETONS).contentType(MediaType.APPLICATION_JSON).content("{not json"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("invalid_request"));
        mvc.perform(post(SKELETONS).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("invalid_request"));
    }

    private boolean serves(String version) {
        return Arrays.stream(mzTabVersions).map(String::strip).anyMatch(version::equals);
    }
}
