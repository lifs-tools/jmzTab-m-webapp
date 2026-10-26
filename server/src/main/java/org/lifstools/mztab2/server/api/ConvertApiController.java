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
import java.io.IOException;
import java.io.InputStream;
import org.lifstools.mztab.validator.webapp.domain.ErrorResponse;
import org.lifstools.mztab.validator.webapp.service.validation.MzTabConverter;
import org.lifstools.mztab.validator.webapp.service.validation.MzTabConverter.Conversion;
import org.lifstools.mztab2.model.MzTab;
import org.lifstools.mztab2.model.ValidationMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Converts mzTab-M between its tab separated form and its JSON
 * representation. A conversion that fails answers 422 with the errors as
 * validation messages. Files up to the upload limit are accepted, as in the
 * validation form; larger ones answer 413. A bounded number of conversions
 * run at once ({@link ConversionSlotInterceptor}); a request that finds no
 * free slot in time answers 503.
 */
@RestController
@RequestMapping(path = "/rest/v2")
@Tag(name = "convert", description = "Conversion between tab separated mzTab-M and its JSON representation.")
public class ConvertApiController {

    static final String TSV = "text/tab-separated-values";

    private final MzTabConverter converter;

    private final long maxBytes;

    public ConvertApiController(MzTabConverter converter,
        @Value("${spring.servlet.multipart.max-file-size:64MB}") DataSize maxUpload) {
        this.converter = converter;
        this.maxBytes = maxUpload.toBytes();
    }

    @Operation(summary = "convertMzTabFile",
        description = "Converts an mzTab-M file in JSON representation to tab separated mzTab-M. Comments are not "
        + "written. Unless validate is false, the written file is parsed again, and its errors are reported with "
        + "422; their line numbers refer to the written file.",
        requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "The mzTab-M file in JSON representation.", required = true,
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = MzTab.class))))
    @ApiResponse(responseCode = "200", description = "The tab separated mzTab-M file",
        content = @Content(mediaType = TSV, schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "400", description = "The request body is not mzTab-M JSON",
        content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "413", description = "The file is larger than the upload limit",
        content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "422", description = "The model could not be written, or the written file has errors",
        content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
            array = @ArraySchema(schema = @Schema(implementation = ValidationMessage.class))))
    @ApiResponse(responseCode = "503", description = "Too many conversions are running; try again after Retry-After seconds",
        content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping(value = "/convert", consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = {TSV, MediaType.TEXT_PLAIN_VALUE})
    public ResponseEntity<?> convertMzTabFile(
        HttpServletRequest request,
        @Parameter(description = "Whether to parse the written file again and report its errors.")
        @RequestParam(name = "validate", defaultValue = "true") boolean validate) throws IOException {
        return respond(converter.write(body(request), maxBytes, validate));
    }

    @Operation(summary = "convertPlainMzTabFile",
        description = "Converts a tab separated mzTab-M file to its JSON representation. A file with errors is "
        + "reported with 422.",
        requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "The tab separated mzTab-M file.", required = true,
            content = {@Content(mediaType = TSV, schema = @Schema(type = "string")),
                @Content(mediaType = MediaType.TEXT_PLAIN_VALUE, schema = @Schema(type = "string"))}))
    @ApiResponse(responseCode = "200", description = "The mzTab-M file in JSON representation",
        content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = MzTab.class)))
    @ApiResponse(responseCode = "413", description = "The file is larger than the upload limit",
        content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "422", description = "The file has errors",
        content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
            array = @ArraySchema(schema = @Schema(implementation = ValidationMessage.class))))
    @ApiResponse(responseCode = "503", description = "Too many conversions are running; try again after Retry-After seconds",
        content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping(value = "/convertPlain", consumes = {TSV, MediaType.TEXT_PLAIN_VALUE},
        produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> convertPlainMzTabFile(
        HttpServletRequest request) throws IOException {
        return respond(converter.read(body(request), maxBytes));
    }

    /** The request body; a body that declares a length over the limit is refused before it is read. */
    private InputStream body(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > maxBytes) {
            throw new MaxUploadSizeExceededException(maxBytes);
        }
        return request.getInputStream();
    }

    /** The converted value, or the errors as JSON whatever the client accepts. */
    private static ResponseEntity<?> respond(Conversion<?> conversion) {
        if (conversion.succeeded()) {
            return ResponseEntity.ok(conversion.value());
        }
        return ResponseEntity.unprocessableEntity().contentType(MediaType.APPLICATION_JSON).body(conversion.errors());
    }
}
