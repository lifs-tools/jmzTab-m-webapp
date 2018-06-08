/**
 * NOTE: This class is auto generated by the swagger code generator program (2.3.0).
 * https://github.com/swagger-api/swagger-codegen
 * Do not edit the class manually.
 */
package de.isas.mztab2.server.api;

import de.isas.mztab2.model.Error;
import de.isas.mztab2.model.ValidationMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.isas.lipidomics.mztab.validator.webapp.domain.UserSessionFile;
import de.isas.lipidomics.mztab.validator.webapp.domain.ValidationLevel;
import de.isas.lipidomics.mztab.validator.webapp.service.StorageService;
import de.isas.lipidomics.mztab.validator.webapp.service.ValidationService;
import io.swagger.annotations.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import java.util.List;
import java.util.Optional;

@javax.annotation.Generated(value = "io.swagger.codegen.languages.SpringCodegen",
    date = "2018-01-11T19:50:29.849+01:00")

@Api(value = "validatePlain", description = "the validatePlain API")
@RequestMapping(path = "/rest/v2")
public interface ValidatePlainApi {

    Logger log = LoggerFactory.getLogger(ValidatePlainApi.class);

    default Optional<ObjectMapper> getObjectMapper() {
        return Optional.empty();
    }

    default Optional<HttpServletRequest> getRequest() {
        return Optional.empty();
    }

    default Optional<String> getAcceptHeader() {
        return getRequest().
            map(r ->
                r.getHeader("Accept"));
    }

    default Optional<ValidationService> getValidationService() {
        return Optional.empty();
    }

    default Optional<StorageService> getStorageService() {
        return Optional.empty();
    }

    @ApiOperation(value = "", nickname = "validatePlainMzTabFile",
        notes = "Validates an mzTab file in plain text representation and reports syntactic, structural, and semantic errors. ",
        response = ValidationMessage.class, responseContainer = "List", tags = {
            "validatePlain",})
    @ApiResponses(value = {
        @ApiResponse(code = 200, message = "Validation Okay",
            response = ValidationMessage.class, responseContainer = "List")
        ,
        @ApiResponse(code = 400, message = "Invalid request",
            response = Error.class)
        ,
        @ApiResponse(code = 415, message = "Unsupported content type")
        ,
        @ApiResponse(code = 422, message = "Invalid input",
            response = ValidationMessage.class, responseContainer = "List")
        ,
        @ApiResponse(code = 500, message = "Unexpected error",
            response = Error.class)})
    @RequestMapping(value = "/validatePlain",
        produces = {"application/json"},
        consumes = {"text/tab-separated-values", "text/plain"},
        method = RequestMethod.POST)
    default ResponseEntity<List<ValidationMessage>> validatePlainMzTabFile(
        @ApiParam(value = "mzTab file that should be validated.",
            required = true) @Valid @RequestBody String mztabfile) {
        if (getObjectMapper().
            isPresent() && getAcceptHeader().
                isPresent()) {
            if (getAcceptHeader().
                get().
                contains("application/json")) {
                UserSessionFile file = getStorageService().
                    get().
                    store(mztabfile, getRequest().
                        get().
                        getSession().
                        getId());
                List<ValidationMessage> messages = getValidationService().
                    get().
                    validate(ValidationService.MzTabVersion.MZTAB_2_0, file, 100,
                        ValidationLevel.INFO, false);
                HttpStatus status = HttpStatus.OK;
                if (messages.size() > 0) {
                    status = HttpStatus.UNPROCESSABLE_ENTITY;
                }
                return new ResponseEntity<>(messages, status);
            }
        } else {
            log.warn(
                "ObjectMapper or HttpServletRequest not configured in default ValidatePlainApi interface so no example is generated");
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).
            build();
    }

}
