package org.lifstools.mztab2.server.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.lifstools.mztab.validator.webapp.service.validation.SynchronousEngineService;
import org.springframework.stereotype.Controller;

@jakarta.annotation.Generated(value = "io.swagger.codegen.languages.SpringCodegen", date = "2018-01-11T19:50:29.849+01:00")
@Controller
public class ValidatePlainApiController implements ValidatePlainApi {

    private final ObjectMapper objectMapper;

    private final HttpServletRequest request;

    private final SynchronousEngineService engineService;

    private final RequestBodies requestBodies;

    @org.springframework.beans.factory.annotation.Autowired
    public ValidatePlainApiController(ObjectMapper objectMapper, HttpServletRequest request,
        SynchronousEngineService engineService, RequestBodies requestBodies) {
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
