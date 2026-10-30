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
package org.lifstools.mztab2.server.api.validation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.lifstools.mztab.validator.webapp.ExampleFileConfig;
import org.lifstools.mztab.validator.webapp.domain.ExampleFile;
import org.lifstools.mztab.validator.engine.api.ValidationLevel;
import org.lifstools.mztab.validator.webapp.session.ValidationOptions;
import org.lifstools.mztab.validator.webapp.session.ValidationRecord;
import org.lifstools.mztab.validator.webapp.session.ValidationSessionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/rest/v2")
@Tag(name = "examples")
public class ExamplesApiController {

    static final String FETCH_FAILED = "The example file could not be downloaded.";

    public record ExampleDto(String id, String name, String section, String description, String version,
        String focus, String createdBy, URI uri) {

    }

    /** All members are optional; defaults are INFO, 100 and structural validation only. */
    public record ExampleValidationRequest(@Nullable ValidationLevel level, @Nullable Integer maxErrors,
        @Nullable String validationProfile, @Nullable String validationProfileVersion) {

    }

    private final ExampleFileConfig config;
    private final ValidationSessionService sessions;
    private final ExampleFetcher fetcher;

    public ExamplesApiController(ExampleFileConfig config, ValidationSessionService sessions,
        ExampleFetcher fetcher) {
        this.config = config;
        this.sessions = sessions;
        this.fetcher = fetcher;
    }

    @Operation(operationId = "listExamples", summary = "Lists the example files")
    @GetMapping(value = "/examples", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<ExampleDto> listExamples() {
        return config.getExampleFile().stream().map(ExamplesApiController::dto).toList();
    }

    @Operation(operationId = "validateExample", summary = "Starts a validation of an example file")
    @PostMapping(value = "/examples/{id}/validations", consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ValidationRecord> validateExample(@PathVariable("id") String id,
        @RequestBody(required = false) @Nullable ExampleValidationRequest body) throws IOException {
        ExampleDto example = config.getExampleFile().stream().map(ExamplesApiController::dto)
            .filter(e -> e.id().equals(id)).findFirst()
            .orElseThrow(() -> new UnknownValidationException("Unknown example."));
        if ("mapping".equals(example.section())) {
            throw new IllegalArgumentException("Mapping files cannot be validated.");
        }
        ExampleValidationRequest req = body == null ? new ExampleValidationRequest(null, null, null, null) : body;
        int maxErrors = req.maxErrors() == null ? 100 : req.maxErrors();
        if (maxErrors < 1 || maxErrors > 500) {
            throw new IllegalArgumentException("maxErrors must be between 1 and 500.");
        }
        ValidationOptions options = new ValidationOptions(req.level() == null ? ValidationLevel.INFO : req.level(),
            maxErrors, blankToNull(req.validationProfile()), blankToNull(req.validationProfileVersion()));
        ValidationRecord record;
        // downloaded completely first: submit holds a global lock while it copies the content
        Path file;
        try {
            file = fetcher.download(example.uri());
        } catch (IOException | RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, FETCH_FAILED, e);
        }
        try (InputStream content = Files.newInputStream(file)) {
            // the file name is the example's own, never client-supplied
            record = sessions.submit(example.name(), content, options, null);
        } finally {
            Files.deleteIfExists(file);
        }
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath().path("/rest/v2/validations/{id}")
            .buildAndExpand(record.id()).toUri();
        return ResponseEntity.accepted().location(location).body(record);
    }

    private static ExampleDto dto(ExampleFile f) {
        return new ExampleDto(f.getName(), f.getName(), f.getSection(), f.getDescription(), f.getVersion(),
            f.getFocus(), f.getCreatedBy(), f.getUri());
    }

    @Nullable
    private static String blankToNull(@Nullable String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
