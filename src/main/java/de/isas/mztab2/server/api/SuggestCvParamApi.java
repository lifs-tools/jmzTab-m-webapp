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
import de.isas.lipidomics.mztab.validator.webapp.domain.UserSessionFile;
import de.isas.lipidomics.mztab.validator.webapp.domain.ValidationLevel;
import de.isas.lipidomics.mztab.validator.webapp.service.StorageService;
import de.isas.lipidomics.mztab.validator.webapp.service.ValidationService;
import de.isas.lipidomics.mztab.validator.webapp.service.cvcompletion.OlsMappingCvSuggestionService;
import de.isas.mztab2.io.MzTabNonValidatingWriter;
import de.isas.mztab2.model.Parameter;
import de.isas.mztab2.model.ValidationMessage;
import static de.isas.mztab2.server.api.ValidateApi.log;
import info.psidev.cvmapping.CvMappingRule;
import info.psidev.cvmapping.CvReference;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import uk.ac.ebi.pride.utilities.ols.web.service.model.Term;

/**
 *
 * @author nils.hoffmann
 */
@Api(value = "suggest", description = "the suggest API")
@RequestMapping(path = "/rest/v2")
public interface SuggestCvParamApi {

    default Optional<ObjectMapper> getObjectMapper() {
        return Optional.empty();
    }

    default Optional<HttpServletRequest> getRequest() {
        return Optional.empty();
    }

    default Optional<String> getAcceptHeader() {
        return getRequest().
                map(r
                        -> r.getHeader("Accept"));
    }

    default Optional<OlsMappingCvSuggestionService> getSuggestionService() {
        return Optional.empty();
    }

    @ApiOperation(value = "", nickname = "getCvReferences",
            notes = "Returns the controlled vocabularies used by the current mapping file.",
            response = CvReference.class, responseContainer = "List", tags = {
                "suggest",})
    @ApiResponses(value = {
        @ApiResponse(code = 200, message = "Request Okay",
                response = CvReference.class, responseContainer = "List"),
        @ApiResponse(code = 404, message = "Not found",
                response = CvReference.class, responseContainer = "List"),
        @ApiResponse(code = 415, message = "Unsupported content type")}
    )
    @RequestMapping(value = "/cvReferences",
            produces = {"application/json"},
            method = RequestMethod.GET)
    default ResponseEntity<List<CvReference>> getCvReferences() {
        if (getObjectMapper().
                isPresent() && getAcceptHeader().
                        isPresent()) {
            if (getAcceptHeader().
                    get().
                    contains("application/json")) {
                OlsMappingCvSuggestionService suggestionService = getSuggestionService().orElseThrow();
                HttpStatus status = HttpStatus.OK;
                List<CvReference> cvReferences = suggestionService.getCvReferences();
                if (cvReferences.isEmpty()) {
                    status = HttpStatus.NOT_FOUND;
                }
                return new ResponseEntity<>(cvReferences, status);
            }
        } else {
            log.warn(
                    "ObjectMapper or HttpServletRequest not configured in default SuggestCvParamApi interface so no example is generated");
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).
                build();
    }

    @ApiOperation(value = "", nickname = "getMappingRules",
            notes = "Returns the mapping rules used by the current mapping file.",
            response = CvMappingRule.class, responseContainer = "List", tags = {
                "suggest",})
    @ApiResponses(value = {
        @ApiResponse(code = 200, message = "Request Okay",
                response = CvMappingRule.class, responseContainer = "List"),
        @ApiResponse(code = 415, message = "Unsupported content type")}
    )
    @RequestMapping(value = "/mappingRules",
            produces = {"application/json"},
            method = RequestMethod.GET)
    default ResponseEntity<List<CvMappingRule>> getMappingRules() {
        if (getObjectMapper().
                isPresent() && getAcceptHeader().
                        isPresent()) {
            if (getAcceptHeader().
                    get().
                    contains("application/json")) {
                OlsMappingCvSuggestionService suggestionService = getSuggestionService().orElseThrow();
                HttpStatus status = HttpStatus.OK;
                List<CvMappingRule> rules = suggestionService.getRules();
                if (rules.isEmpty()) {
                    status = HttpStatus.NOT_FOUND;
                }
                return new ResponseEntity<>(rules, status);
            }
        } else {
            log.warn(
                    "ObjectMapper or HttpServletRequest not configured in default SuggestCvParamApi interface so no example is generated");
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).
                build();
    }

    @ApiOperation(value = "", nickname = "suggestParameters",
            notes = "Suggest possible parameter completions for the current rule scope.",
            response = Parameter.class, responseContainer = "List", tags = {
                "suggest",})
    @ApiResponses(value = {
        @ApiResponse(code = 200, message = "Request Okay",
                response = Term.class, responseContainer = "List"),
        @ApiResponse(code = 404, message = "Not found",
                response = Term.class, responseContainer = "List"),
        @ApiResponse(code = 415, message = "Unsupported content type"),
        @ApiResponse(code = 422, message = "Invalid input",
                response = Term.class, responseContainer = "List"),
        @ApiResponse(code = 500, message = "Unexpected error",
                response = de.isas.mztab2.model.Error.class)})
    @RequestMapping(value = "/suggest",
            produces = {"application/json"},
            method = RequestMethod.GET)
    default ResponseEntity<List<Term>> suggestParameters(
            @RequestParam(
                    value = "partialParamName",
                    //                    value = "The partial term accession or name to complete.",
                    defaultValue = "",
                    required = true) @Valid String partialParamName,
            @RequestParam(
                    value = "parentRuleId",
                    //                    value = "The parent term id, eg. MS:12907. The prefix must match one of the vocabulary ids defined in the mapping file.",
                    defaultValue = "",
                    required = true) @Valid String parentRuleId,
            @RequestParam(
                    value = "levels",
                    //                    value = "The maximum number of levels to descend from the parent to find a matching term.",
                    defaultValue = "5",
                    required = false) @Valid
            @Min(0)
            @Max(500) Integer levels
    ) {
        if (getObjectMapper().
                isPresent() && getAcceptHeader().
                        isPresent()) {
            if (getAcceptHeader().
                    get().
                    contains("application/json")) {
                OlsMappingCvSuggestionService suggestionService = getSuggestionService().orElseThrow();
                HttpStatus status = HttpStatus.OK;
                List<Term> parameters = suggestionService.suggestParameters(partialParamName, parentRuleId, levels);
                if (parameters.isEmpty()) {
                    status = HttpStatus.NOT_FOUND;
                }
                return new ResponseEntity<>(parameters, status);
            }
        } else {
            log.warn(
                    "ObjectMapper or HttpServletRequest not configured in default ValidateApi interface so no example is generated");
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).
                build();
    }
}
