/*
 * Copyright 2017 Leibniz Institut für Analytische Wissenschaften - ISAS e.V..
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
package org.lifstools.mztab2.server.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.lifstools.mztab.validator.webapp.service.validation.SynchronousEngineService;
import org.springframework.stereotype.Controller;

@jakarta.annotation.Generated(value = "io.swagger.codegen.languages.SpringCodegen",
    date = "2018-01-11T19:50:29.849+01:00")
@Controller
public class ValidateApiController implements ValidateApi {

    private final ObjectMapper objectMapper;

    private final HttpServletRequest request;

    private final SynchronousEngineService engineService;

    private final RequestBodies requestBodies;

    @org.springframework.beans.factory.annotation.Autowired
    public ValidateApiController(ObjectMapper objectMapper,
        HttpServletRequest request, SynchronousEngineService engineService, RequestBodies requestBodies) {
        this.objectMapper = objectMapper;
        this.request = request;
        this.engineService = engineService;
        this.requestBodies = requestBodies;
    }

    @Override
    public Optional<ObjectMapper> getObjectMapper() {
        return Optional.ofNullable(objectMapper);
    }

    @Override
    public Optional<HttpServletRequest> getRequest() {
        return Optional.ofNullable(request);
    }

    @Override
    public Optional<SynchronousEngineService> getEngineService() {
        return Optional.ofNullable(engineService);
    }

    @Override
    public Optional<RequestBodies> getRequestBodies() {
        return Optional.ofNullable(requestBodies);
    }
}
