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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.lifstools.mztab2.cvmapping.CvParameterLookupService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import uk.ac.ebi.pride.utilities.ols.web.service.client.OLSClient;
import uk.ac.ebi.pride.utilities.ols.web.service.config.OLSWsConfig;

/**
 * The engine's beans: the CV lookup for semantic validation (as in the
 * webapp's {@code WebConfig}), the JSON mapper of the mzTab-M model (as in the
 * webapp's {@code JacksonConfiguration}), the job limit and the OpenAPI
 * description of the engine contract.
 */
@Configuration
public class EngineConfig {

    /** Together these give {@code Constants.OLS_PROTOCOL} and {@code Constants.OLS_SERVER} of ols-client. */
    static final String DEFAULT_OLS_PROTOCOL = "https";
    static final String DEFAULT_OLS_HOST = "www.ebi.ac.uk";
    static final String DEFAULT_OLS_BASE_PATH = "ols";

    static final String DEFAULT_MAX_JOBS = "#{T(java.lang.Runtime).getRuntime().availableProcessors()}";

    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, false);
        return mapper;
    }

    /**
     * The Ontology Lookup Service client of semantic validation. The endpoint
     * is {@code <engine.ols.protocol>://<engine.ols.host>/<engine.ols.base-path>/api/...};
     * the defaults are the library's own ({@code https://www.ebi.ac.uk/ols/}).
     * The host may carry a port. The client keeps only the first path segment
     * of its host name, so the base path must be a single segment.
     */
    @Bean
    public OLSClient olsClient(
            @Value("${engine.ols.protocol:" + DEFAULT_OLS_PROTOCOL + "}") String protocol,
            @Value("${engine.ols.host:" + DEFAULT_OLS_HOST + "}") String host,
            @Value("${engine.ols.base-path:" + DEFAULT_OLS_BASE_PATH + "}") String basePath) {
        String segment = basePath.strip();
        if (segment.startsWith("/")) {
            segment = segment.substring(1);
        }
        if (segment.endsWith("/")) {
            segment = segment.substring(0, segment.length() - 1);
        }
        if (segment.isEmpty() || segment.contains("/")) {
            throw new IllegalArgumentException("engine.ols.base-path must be a single path segment, e.g. '"
                + DEFAULT_OLS_BASE_PATH + "', but was '" + basePath + "'");
        }
        return new OLSClient(new OLSWsConfig(protocol.strip(), host.strip() + "/" + segment + "/"));
    }

    @Bean
    public CvParameterLookupService cvParameterLookupService(OLSClient client) {
        return new CvParameterLookupService(client);
    }

    @Bean
    public JobLimiter jobLimiter(@Value("${engine.max-concurrent-jobs:" + DEFAULT_MAX_JOBS + "}") int maxConcurrent) {
        return new JobLimiter(maxConcurrent);
    }

    /** The same for every engine: the contract does not name a line or a jmzTab-m version. */
    @Bean
    public OpenAPI engineOpenApi() {
        return new OpenAPI().info(new Info()
            .title("mzTab-M validation engine")
            .version("v1")
            .description("Validates and converts mzTab-M files of one version line with the jmzTab-m library of "
                + "that line. GET /engine/v1/info names the line. Internal API of the mzTab-M validator webapp; "
                + "no authentication.")
            .license(new License().name("Apache 2.0").url("http://www.apache.org/licenses/LICENSE-2.0.html")));
    }
}
