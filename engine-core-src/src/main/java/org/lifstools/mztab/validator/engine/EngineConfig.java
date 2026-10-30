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

    @Bean
    public OLSClient olsClient() {
        OLSWsConfig config = new OLSWsConfig();
        return new OLSClient(config);
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
