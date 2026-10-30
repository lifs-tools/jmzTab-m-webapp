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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.lifstools.mztab.validator.engine.api.EngineError;
import org.lifstools.mztab.validator.engine.api.EngineErrorCode;
import org.lifstools.mztab.validator.engine.api.EngineInfo;
import org.lifstools.mztab.validator.engine.api.MzTabVersionDetector;
import org.lifstools.mztab.validator.engine.api.MzTabVersions;
import org.lifstools.mztab.validator.engine.api.ValidationEvent;
import org.lifstools.mztab.validator.engine.api.ValidationLevel;
import org.lifstools.mztab.validator.engine.api.ValidationMessage;
import org.lifstools.mztab2.io.MzTabNonValidatingWriter;
import org.lifstools.mztab2.model.MzTab;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
import uk.ac.ebi.pride.jmztab2.utils.errors.MZTabException;

/**
 * The engine API: what the engine is, validation of one file as an NDJSON
 * stream, and conversion between tab separated mzTab-M and JSON. A file that
 * declares a version of another line is refused, so a file only ever reaches
 * the jmzTab-m of its own line.
 */
@Slf4j
@RestController
@RequestMapping("/engine/v1")
@Tag(name = "engine", description = "mzTab-M validation and conversion with the jmzTab-m of one version line")
public class EngineController {

    static final String NDJSON = "application/x-ndjson";
    static final String TSV = "text/tab-separated-values";
    /** The largest accepted mapping file (5 MiB), as in the webapp. */
    static final long MAX_MAPPING_BYTES = 5L * 1024 * 1024;
    static final String JSON_NOT_READ = "The JSON file could not be read: ";
    static final String JSON_NOT_CONVERTED = "The JSON file could not be converted to mzTab-M: ";
    static final String BUSY = "The engine is running as many jobs as it may. Please try again shortly.";
    /** Longest parser message kept after {@link #JSON_NOT_READ}. */
    private static final int MAX_PARSER_MESSAGE = 500;
    private static final Pattern SOURCE_LOCATION = Pattern.compile("\\[Source: [^;\\]]*; (line: \\d+, column: \\d+)\\]");

    private final EngineValidator validator;
    private final EngineConverter converter;
    private final ValidationStreamWriter streamWriter;
    private final JobLimiter limiter;
    private final ObjectMapper objectMapper;
    private final String line;
    private final List<String> mzTabVersions;
    private final String jmztabmVersion;
    private final long maxUploadBytes;
    private final String build;

    public EngineController(EngineValidator validator, EngineConverter converter, ValidationStreamWriter streamWriter,
        JobLimiter limiter, ObjectMapper objectMapper, @Value("${engine.line}") String line,
        @Value("${engine.mztab-versions}") String[] mzTabVersions,
        @Value("${engine.jmztabm-version}") String jmztabmVersion,
        @Value("${spring.servlet.multipart.max-file-size:64MB}") DataSize maxUpload,
        ObjectProvider<BuildProperties> buildProperties) {
        this.validator = validator;
        this.converter = converter;
        this.streamWriter = streamWriter;
        this.limiter = limiter;
        this.objectMapper = objectMapper;
        this.line = line;
        this.mzTabVersions = Arrays.stream(mzTabVersions).map(String::strip).toList();
        this.jmztabmVersion = jmztabmVersion;
        this.maxUploadBytes = maxUpload.toBytes();
        BuildProperties properties = buildProperties.getIfAvailable();
        Instant time = properties == null ? null : properties.getTime();
        this.build = time == null ? "unknown" : time.toString();
    }

    @Operation(summary = "info", description = "The version line this engine validates, the declared versions it "
        + "accepts, its jmzTab-m version and its limits.")
    @ApiResponse(responseCode = "200", description = "The engine",
        content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = EngineInfo.class)))
    @GetMapping(value = "/info", produces = MediaType.APPLICATION_JSON_VALUE)
    public EngineInfo info() {
        return new EngineInfo(line, mzTabVersions, jmztabmVersion, build, maxUploadBytes, limiter.maxConcurrent());
    }

    @Operation(summary = "validate", description = "Validates one mzTab-M file, tab separated or JSON, and streams "
        + "the result as NDJSON: one outcome event; if the file was parsed, its metadata events and, for each "
        + "present table (SML, SMF, SME), one table event followed by its row events; then one end event. A stream "
        + "without the end event is incomplete. Row cells exclude the line prefix. A file that declares no "
        + "mzTab-version is validated and reported by jmzTab-m.")
    @ApiResponse(responseCode = "200", description = "The validation result as NDJSON, one event per line",
        content = @Content(mediaType = NDJSON, schema = @Schema(implementation = ValidationEvent.class)))
    @ApiResponse(responseCode = "400", description = "A parameter is invalid (invalid_request), or the file declares "
        + "a version of another line (unsupported_version)",
        content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = EngineError.class)))
    @ApiResponse(responseCode = "413", description = "The file or the mapping is larger than its limit (file_too_large)",
        content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = EngineError.class)))
    @ApiResponse(responseCode = "503", description = "The engine is busy (busy); retry after Retry-After seconds",
        content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = EngineError.class)))
    @PostMapping(value = "/validations", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public void validate(
        @Parameter(description = "The mzTab-M file, tab separated or JSON; at most the upload limit of GET /info")
        @RequestPart("file") MultipartFile file,
        @Parameter(description = "A CV mapping file for semantic validation, at most 5 MiB; without it, only the "
            + "structure is validated")
        @RequestPart(value = "mapping", required = false) @Nullable MultipartFile mapping,
        @Parameter(description = "The lowest level of messages to report")
        @RequestParam(name = "level", defaultValue = "INFO") ValidationLevel level,
        @Parameter(description = "The maximum number of messages the parser reports, and the maximum number of "
            + "semantic messages")
        @RequestParam(name = "maxErrors", defaultValue = "100") @Min(0) @Max(500) int maxErrors,
        HttpServletResponse response) throws IOException {
        if (file.getSize() > maxUploadBytes) {
            throw new MaxUploadSizeExceededException(maxUploadBytes);
        }
        if (mapping != null && mapping.getSize() > MAX_MAPPING_BYTES) {
            throw new MaxUploadSizeExceededException(MAX_MAPPING_BYTES);
        }
        Path upload = null;
        Path tsv = null;
        Path mappingFile = null;
        try (JobLimiter.Permit permit = acquire()) {
            upload = spool(file, ".mztab");
            boolean json = MzTabVersionDetector.isJson(upload);
            Optional<String> version = declaredVersion(upload, json);
            requireLine(version);
            Path validated;
            if (json) {
                MzTab parsed;
                try (InputStream in = Files.newInputStream(upload)) {
                    parsed = objectMapper.readValue(in, MzTab.class);
                } catch (JsonProcessingException e) {
                    log.info("The JSON upload could not be read: {}", e.getOriginalMessage());
                    notParsed(response, version, JSON_NOT_READ + shorten(jsonDetail(e)));
                    return;
                }
                // jmzTab-m validates files: write the model as tab separated mzTab-M
                tsv = Files.createTempFile("mztab-validation-", ".mztab");
                try (OutputStreamWriter out = new OutputStreamWriter(Files.newOutputStream(tsv),
                    StandardCharsets.UTF_8)) {
                    new MzTabNonValidatingWriter().write(out, parsed);
                } catch (IOException | RuntimeException e) {
                    // e.g. a model without metadata: the writer reports the mzTab error
                    log.info("The JSON upload could not be written as mzTab-M", e);
                    Throwable cause = e.getCause() instanceof MZTabException ? e.getCause() : e;
                    notParsed(response, version, JSON_NOT_CONVERTED + shorten(cause.getMessage() == null
                        ? cause.getClass().getSimpleName() : cause.getMessage().strip()));
                    return;
                }
                validated = tsv;
            } else {
                validated = upload;
            }
            mappingFile = mapping == null ? null : spool(mapping, ".xml");
            EngineValidator.Outcome outcome = validator.validate(validated, level, maxErrors, mappingFile);
            MzTab mzTab = outcome.mzTab();
            String mzTabVersion = version.orElseGet(() -> mzTab == null || mzTab.getMetadata() == null
                ? null : mzTab.getMetadata().getMzTabVersion());
            stream(response, mzTabVersion, outcome, mzTab == null ? null : render(mzTab));
        } finally {
            deleteQuietly(upload);
            deleteQuietly(tsv);
            deleteQuietly(mappingFile);
        }
    }

    @Operation(summary = "tsvToJson", description = "Converts a tab separated mzTab-M file to its JSON "
        + "representation. A file with errors is reported with 422.",
        requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "The tab separated mzTab-M file.", required = true,
            content = @Content(mediaType = TSV, schema = @Schema(type = "string"))))
    @ApiResponse(responseCode = "200", description = "The mzTab-M model of the engine's jmzTab-m line as JSON",
        content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(type = "object")))
    @ApiResponse(responseCode = "400", description = "The file declares a version of another line (unsupported_version)",
        content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = EngineError.class)))
    @ApiResponse(responseCode = "413", description = "The file is larger than the upload limit (file_too_large)",
        content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = EngineError.class)))
    @ApiResponse(responseCode = "422", description = "The file has errors",
        content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
            array = @ArraySchema(schema = @Schema(implementation = ValidationMessage.class))))
    @ApiResponse(responseCode = "503", description = "The engine is busy (busy); retry after Retry-After seconds",
        content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = EngineError.class)))
    @PostMapping(value = "/conversions/tsv-to-json")
    public ResponseEntity<?> tsvToJson(HttpServletRequest request) throws IOException {
        Path body = null;
        try (JobLimiter.Permit permit = acquire()) {
            body = receive(request);
            requireLine(declaredVersion(body, false));
            return respond(converter.read(body));
        } finally {
            deleteQuietly(body);
        }
    }

    @Operation(summary = "jsonToTsv", description = "Converts an mzTab-M file in JSON representation to tab "
        + "separated mzTab-M. Comments are not written. Unless validate is false, the written file is parsed "
        + "again, and its errors are reported with 422; their line numbers refer to the written file.",
        requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "The mzTab-M file in JSON representation.", required = true,
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(type = "object"))))
    @ApiResponse(responseCode = "200", description = "The tab separated mzTab-M file",
        content = @Content(mediaType = TSV, schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "400", description = "The body is not mzTab-M JSON (not_json), or it declares a "
        + "version of another line (unsupported_version)",
        content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = EngineError.class)))
    @ApiResponse(responseCode = "413", description = "The file is larger than the upload limit (file_too_large)",
        content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = EngineError.class)))
    @ApiResponse(responseCode = "422", description = "The model could not be written, or the written file has errors",
        content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
            array = @ArraySchema(schema = @Schema(implementation = ValidationMessage.class))))
    @ApiResponse(responseCode = "503", description = "The engine is busy (busy); retry after Retry-After seconds",
        content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = EngineError.class)))
    @PostMapping(value = "/conversions/json-to-tsv")
    public ResponseEntity<?> jsonToTsv(HttpServletRequest request,
        @Parameter(description = "Whether to parse the written file again and report its errors.")
        @RequestParam(name = "validate", defaultValue = "true") boolean validate) throws IOException {
        Path body = null;
        try (JobLimiter.Permit permit = acquire()) {
            body = receive(request);
            requireLine(declaredVersion(body, true));
            EngineConverter.Conversion<String> conversion;
            try {
                conversion = converter.write(body, validate);
            } catch (IllegalArgumentException e) {
                throw new EngineException(HttpStatus.BAD_REQUEST, EngineErrorCode.NOT_JSON, shorten(e.getMessage()));
            }
            if (conversion.succeeded()) {
                return ResponseEntity.ok().contentType(new MediaType("text", "tab-separated-values",
                    StandardCharsets.UTF_8)).body(conversion.value());
            }
            return respond(conversion);
        } finally {
            deleteQuietly(body);
        }
    }

    private JobLimiter.Permit acquire() {
        return limiter.tryAcquire()
            .orElseThrow(() -> new EngineException(HttpStatus.SERVICE_UNAVAILABLE, EngineErrorCode.BUSY, BUSY));
    }

    /**
     * The declared version. A file that cannot be inspected, e.g. JSON that is
     * not well formed or text that is not UTF-8, declares none; jmzTab-m or the
     * JSON reader reports it.
     */
    private static Optional<String> declaredVersion(Path file, boolean json) throws IOException {
        try {
            return json ? MzTabVersionDetector.detectJson(file) : MzTabVersionDetector.detectTsv(file);
        } catch (MalformedInputException | JsonProcessingException e) {
            return Optional.empty();
        }
    }

    /** Refuses a file whose declared version belongs to another line. */
    private void requireLine(Optional<String> declared) {
        if (declared.isEmpty()) {
            return;
        }
        Optional<String> declaredLine = MzTabVersions.lineOf(declared.get());
        if (declaredLine.isPresent() && !declaredLine.get().equals(line)) {
            throw new EngineException(HttpStatus.BAD_REQUEST, EngineErrorCode.UNSUPPORTED_VERSION,
                "This file declares mzTab-version " + declared.get().strip() + ". This engine validates mzTab-M "
                + line + " files (" + String.join(", ", mzTabVersions) + ").");
        }
    }

    private static Path spool(MultipartFile part, String suffix) throws IOException {
        Path file = Files.createTempFile("mztab-validation-", suffix);
        try (InputStream in = part.getInputStream()) {
            Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
            return file;
        } catch (IOException | RuntimeException e) {
            deleteQuietly(file);
            throw e;
        }
    }

    /** The request body as a temporary file; more than the upload limit throws {@link MaxUploadSizeExceededException}. */
    private Path receive(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > maxUploadBytes) {
            throw new MaxUploadSizeExceededException(maxUploadBytes);
        }
        return EngineConverter.receive(new CappedInputStream(request.getInputStream(), maxUploadBytes,
            () -> new MaxUploadSizeExceededException(maxUploadBytes)), null);
    }

    /** The sections, or {@code null} if they cannot be rendered: the validation result is kept without them. */
    @Nullable
    private static SectionRenderer.Rendered render(MzTab mzTab) {
        try {
            return SectionRenderer.render(mzTab);
        } catch (IOException | RuntimeException e) {
            log.error("Could not render the sections; the result is streamed without them", e);
            return null;
        }
    }

    private void stream(HttpServletResponse response, @Nullable String mzTabVersion, EngineValidator.Outcome outcome,
        @Nullable SectionRenderer.Rendered rendered) throws IOException {
        response.setStatus(HttpStatus.OK.value());
        response.setContentType(NDJSON);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        streamWriter.write(response.getOutputStream(), mzTabVersion, outcome, rendered);
    }

    /** A JSON upload that never reached the validator: one ERROR message. */
    private void notParsed(HttpServletResponse response, Optional<String> version, String message) throws IOException {
        stream(response, version.orElse(null), new EngineValidator.Outcome(List.of(ValidationMessage.error(message)),
            null), null);
    }

    /** The converted value, or the errors as JSON whatever the client accepts. */
    private static ResponseEntity<?> respond(EngineConverter.Conversion<?> conversion) {
        if (conversion.succeeded()) {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(conversion.value());
        }
        return ResponseEntity.unprocessableEntity().contentType(MediaType.APPLICATION_JSON).body(conversion.errors());
    }

    /** The parser's own message and its position, without the source excerpt or a stack trace. */
    private static String jsonDetail(JsonProcessingException e) {
        String detail = e.getOriginalMessage() == null ? e.getClass().getSimpleName() : e.getOriginalMessage();
        // nested locations, e.g. of a start marker, name a redacted source: keep line and column only
        detail = shorten(SOURCE_LOCATION.matcher(detail).replaceAll("[$1]"));
        if (e.getLocation() != null && e.getLocation().getLineNr() > 0) {
            detail += " (line " + e.getLocation().getLineNr() + ", column " + e.getLocation().getColumnNr() + ")";
        }
        return detail;
    }

    private static String shorten(String text) {
        return text.length() > MAX_PARSER_MESSAGE ? text.substring(0, MAX_PARSER_MESSAGE) + "..." : text;
    }

    private static void deleteQuietly(@Nullable Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Could not delete temporary file {}", file, e);
        }
    }
}
