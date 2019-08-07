/**
 * NOTE: This class is auto generated by the swagger code generator program (2.3.0).
 * https://github.com/swagger-api/swagger-codegen
 * Do not edit the class manually.
 */
package de.isas.mztab2.server.api;

import de.isas.mztab2.model.Error;
import de.isas.mztab2.model.MzTab;
import de.isas.mztab2.model.ValidationMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.isas.lipidomics.mztab.validator.webapp.domain.UserSessionFile;
import de.isas.lipidomics.mztab.validator.webapp.domain.ValidationLevel;
import de.isas.lipidomics.mztab.validator.webapp.service.StorageService;
import de.isas.lipidomics.mztab.validator.webapp.service.StorageService.SLOT;
import de.isas.lipidomics.mztab.validator.webapp.service.ValidationService;
import de.isas.mztab2.io.MzTabNonValidatingWriter;
import io.swagger.annotations.*;
import java.io.ByteArrayOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import org.springframework.web.bind.annotation.RequestParam;

@javax.annotation.Generated(value = "io.swagger.codegen.languages.SpringCodegen",
    date = "2018-01-11T19:50:29.849+01:00")

@Api(value = "validate", description = "the validate API")
@RequestMapping(path = "/rest/v2")
public interface ValidateApi {

    Logger log = LoggerFactory.getLogger(ValidateApi.class);

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

    @ApiOperation(value = "", nickname = "validateMzTabFile",
        notes = "Validates an mzTab file in XML or JSON representation and reports syntactic, structural, and semantic errors.",
        response = ValidationMessage.class, responseContainer = "List", tags = {
            "validate",})
    @ApiResponses(value = {
        @ApiResponse(code = 200, message = "Validation Okay",
            response = ValidationMessage.class, responseContainer = "List")
        ,
        @ApiResponse(code = 415, message = "Unsupported content type")
        ,
        @ApiResponse(code = 422, message = "Invalid input",
            response = ValidationMessage.class, responseContainer = "List")
        ,
        @ApiResponse(code = 500, message = "Unexpected error",
            response = Error.class)})
    @RequestMapping(value = "/validate",
        produces = {"application/json"},
        consumes = {"application/json", "application/xml"},
        method = RequestMethod.POST)
    default ResponseEntity<List<ValidationMessage>> validateMzTabFile(@ApiParam(
        value = "mzTab file that should be validated.", required = true) @RequestBody MzTab mztabfile,
        @RequestParam(
            value = "The level of errors that should be reported, one of error, warn, info.",
            defaultValue = "info",
            required = false) @Valid String level,
        @RequestParam(
            value = "The maximum number of errors to return.",
            defaultValue = "100",
            required = false) @Valid @Min(0) @Max(500) Integer maxErrors,
        @RequestParam(
            value = "Whether a semantic validation against the default rule set should be performed.",
            defaultValue = "false",
            required = false) @Valid boolean semanticValidation) {
        if (getObjectMapper().
            isPresent() && getAcceptHeader().
                isPresent()) {
            if (getAcceptHeader().
                get().
                contains("application/json")) {
                try {
                    MzTabNonValidatingWriter writer = new MzTabNonValidatingWriter();
                    ByteArrayOutputStream stringWriter = new ByteArrayOutputStream();
                    writer.
                        write(new OutputStreamWriter(stringWriter), mztabfile);
                    String mzTabString = stringWriter.toString("UTF-8");
                    UserSessionFile file = getStorageService().
                        get().
                        store(mzTabString, UUID.randomUUID(), SLOT.MZTABFILE);
                    UserSessionFile mappingFile = getStorageService().
                        get().
                        store(ValidateApi.class.getResource(
                            "/mappings/mzTab-M-mapping.xml"), file.
                                getSessionId(), SLOT.MAPPINGFILE);
                    List<ValidationMessage> messages = getValidationService().
                        get().
                        validate(ValidationService.MzTabVersion.MZTAB_2_0, file,
                            maxErrors, ValidationLevel.valueOf(
                                level == null ? "INFO" : level.toUpperCase()),
                            semanticValidation, mappingFile);
                    messages = messages.subList(0, Math.min(messages.size(),
                        maxErrors));
                    HttpStatus status = HttpStatus.OK;
                    if (messages.size() > 0) {
                        status = HttpStatus.UNPROCESSABLE_ENTITY;
                    }
                    return new ResponseEntity<>(messages, status);
                } catch (IOException e) {
                    log.error(
                        "Couldn't serialize response for content type application/json",
                        e);
                    return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
                }
            }
        } else {
            log.warn(
                "ObjectMapper or HttpServletRequest not configured in default ValidateApi interface so no example is generated");
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).
            build();
    }

}
