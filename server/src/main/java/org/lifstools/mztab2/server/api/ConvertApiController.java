/*
 * Copyright 2022 Lipidomics Informatics for Life Sciences.
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

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.lifstools.mztab.validator.engine.api.ValidationMessage;
import org.lifstools.mztab.validator.webapp.domain.ErrorResponse;
import org.lifstools.mztab.validator.webapp.service.validation.SynchronousEngineService;
import org.lifstools.mztab.validator.webapp.service.validation.SynchronousEngineService.Conversion;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Converts mzTab-M between its tab separated form and its JSON
 * representation, through the engine of the file's declared version line. A
 * conversion that fails, or a file of a version that this instance does not
 * convert, answers 422 with the errors as validation messages. Files up to the
 * upload limit are accepted, as in the validation form; larger ones answer
 * 413. The body is spooled to a temporary file and the converted file is
 * streamed from the engine's result, so neither is held in memory. A bounded
 * number of conversions run at once ({@link SynchronousSlotInterceptor}); a
 * request that finds no free slot in time, or a busy or unavailable engine,
 * answers 503.
 */
@RestController
@RequestMapping(path = "/rest/v2")
@Tag(name = "convert", description = "Conversion between tab separated mzTab-M and its JSON representation.")
public class ConvertApiController {

    static final String TSV = "text/tab-separated-values";

    private static final MediaType TSV_TYPE = MediaType.parseMediaType(TSV);

    private final SynchronousEngineService engines;

    private final RequestBodies bodies;

    public ConvertApiController(SynchronousEngineService engines, RequestBodies bodies) {
        this.engines = engines;
        this.bodies = bodies;
    }

    @Operation(summary = "convertMzTabFile",
        description = "Converts an mzTab-M file in JSON representation to tab separated mzTab-M. Comments are not "
        + "written. Unless validate is false, the written file is parsed again, and its errors are reported with "
        + "422; their line numbers refer to the written file. The file's declared mzTab-version decides the model "
        + "the JSON is read with.",
        requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "The mzTab-M file in JSON representation.", required = true,
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(type = "object"))))
    @ApiResponse(responseCode = "200", description = "The tab separated mzTab-M file",
        content = @Content(mediaType = TSV, schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "400", description = "The request body is not mzTab-M JSON",
        content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "413", description = "The file is larger than the upload limit",
        content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "422", description = "The model could not be written, the written file has errors, "
        + "or this instance does not convert the file's mzTab-M version",
        content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
            array = @ArraySchema(schema = @Schema(implementation = ValidationMessage.class))))
    @ApiResponse(responseCode = "503", description = "Too many files are being validated or converted, or the "
        + "converter of the file's version is not available; try again after Retry-After seconds",
        content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping(value = "/convert", consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = {TSV, MediaType.TEXT_PLAIN_VALUE})
    public ResponseEntity<?> convertMzTabFile(
        HttpServletRequest request, HttpServletResponse response,
        @Parameter(description = "Whether to parse the written file again and report its errors.")
        @RequestParam(name = "validate", defaultValue = "true") boolean validate) throws IOException {
        Path json = bodies.spool(request, ".json");
        try {
            return respond(engines.jsonToTsv(json, validate), response, tabSeparatedType(request));
        } finally {
            SynchronousEngineService.deleteQuietly(json);
        }
    }

    @Operation(summary = "convertPlainMzTabFile",
        description = "Converts a tab separated mzTab-M file to its JSON representation, in the model of the file's "
        + "declared mzTab-version. A file with errors is reported with 422.",
        requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "The tab separated mzTab-M file.", required = true,
            content = {@Content(mediaType = TSV, schema = @Schema(type = "string")),
                @Content(mediaType = MediaType.TEXT_PLAIN_VALUE, schema = @Schema(type = "string"))}))
    @ApiResponse(responseCode = "200", description = "The mzTab-M file in JSON representation",
        content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(type = "object")))
    @ApiResponse(responseCode = "413", description = "The file is larger than the upload limit",
        content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "422", description = "The file has errors, or this instance does not convert its "
        + "mzTab-M version",
        content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
            array = @ArraySchema(schema = @Schema(implementation = ValidationMessage.class))))
    @ApiResponse(responseCode = "503", description = "Too many files are being validated or converted, or the "
        + "converter of the file's version is not available; try again after Retry-After seconds",
        content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping(value = "/convertPlain", consumes = {TSV, MediaType.TEXT_PLAIN_VALUE},
        produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> convertPlainMzTabFile(
        HttpServletRequest request, HttpServletResponse response) throws IOException {
        Path tsv = bodies.spool(request, ".mztab");
        try {
            return respond(engines.tsvToJson(tsv), response, MediaType.APPLICATION_JSON);
        } finally {
            SynchronousEngineService.deleteQuietly(tsv);
        }
    }

    /**
     * The errors as JSON whatever the client accepts; or the converted file,
     * streamed to the response, which is then complete ({@code null}).
     */
    @Nullable
    private static ResponseEntity<List<ValidationMessage>> respond(Conversion conversion,
        HttpServletResponse response, MediaType type) throws IOException {
        if (conversion instanceof Conversion.Rejected rejected) {
            return ResponseEntity.unprocessableEntity().contentType(MediaType.APPLICATION_JSON)
                .body(rejected.messages());
        }
        Path file = ((Conversion.Converted) conversion).file();
        try {
            response.setStatus(HttpStatus.OK.value());
            response.setHeader(HttpHeaders.CONTENT_TYPE, type.toString());
            response.setContentLengthLong(Files.size(file));
            OutputStream out = response.getOutputStream();
            Files.copy(file, out);
            out.flush();
            return null;
        } finally {
            SynchronousEngineService.deleteQuietly(file);
        }
    }

    /**
     * Tab separated or plain text, as the client prefers; both UTF-8. Spring
     * has already refused a client that accepts neither.
     */
    static MediaType tabSeparatedType(HttpServletRequest request) {
        List<MediaType> accepted;
        try {
            String accept = request.getHeader(HttpHeaders.ACCEPT);
            accepted = accept == null || accept.isBlank() ? List.of() : MediaType.parseMediaTypes(accept);
        } catch (RuntimeException e) {
            accepted = List.of();
        }
        MediaType type = quality(accepted, TSV_TYPE) >= quality(accepted, MediaType.TEXT_PLAIN) ? TSV_TYPE
            : MediaType.TEXT_PLAIN;
        return new MediaType(type, StandardCharsets.UTF_8);
    }

    /** The highest quality with which the client accepts {@code type}; 1 if it states no preference. */
    private static double quality(List<MediaType> accepted, MediaType type) {
        if (accepted.isEmpty()) {
            return 1;
        }
        return accepted.stream().filter(a -> a.includes(type)).mapToDouble(MediaType::getQualityValue).max()
            .orElse(0);
    }
}
