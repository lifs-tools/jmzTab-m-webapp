/*
 * Copyright 2019 Leibniz Institut für Analytische Wissenschaften - ISAS e.V..
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
package de.isas.mztab2.server.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.isas.lipidomics.mztab.validator.webapp.service.cvcompletion.OlsMappingCvSuggestionService;
import java.util.Optional;
import javax.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;

/**
 *
 * @author nils.hoffmann
 */
@Slf4j
@Controller
public class SuggestCvParamApiController implements SuggestCvParamApi {

    private final ObjectMapper objectMapper;

    private final HttpServletRequest request;

    private final OlsMappingCvSuggestionService completionService;

    @Autowired
    public SuggestCvParamApiController(ObjectMapper objectMapper,
            HttpServletRequest request, OlsMappingCvSuggestionService completionService) {
        this.objectMapper = objectMapper;
        this.request = request;
        this.completionService = completionService;
    }

    @Override
    public Optional<OlsMappingCvSuggestionService> getSuggestionService() {
        return Optional.ofNullable(completionService);
    }

    @Override
    public Optional<ObjectMapper> getObjectMapper() {
        return Optional.ofNullable(objectMapper);
    }

    @Override
    public Optional<HttpServletRequest> getRequest() {
        return Optional.ofNullable(request);
    }

}
