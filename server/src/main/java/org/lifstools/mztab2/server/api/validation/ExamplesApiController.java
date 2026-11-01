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
import jakarta.validation.constraints.Pattern;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.lifstools.mztab.validator.webapp.ExampleFileConfig;
import org.lifstools.mztab.validator.webapp.domain.ExampleFile;
import org.lifstools.mztab.validator.engine.api.MzTabVersions;
import org.lifstools.mztab.validator.engine.api.ValidationLevel;
import org.lifstools.mztab.validator.webapp.engine.EngineRegistry;
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
    static final String UNKNOWN_EXAMPLE = "Unknown example.";
    /** An example id in a path; every configured name matches it. */
    static final String ID_PATTERN = "^[A-Za-z0-9._-]{1,100}$";

    private static final MediaType TEXT_PLAIN_UTF8 = new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8);

    /**
     * An example file.
     *
     * @param uri where the file can be downloaded; for a bundled example the relative
     * {@code rest/v2/examples/{id}/content}
     * @param originalUri where the file a bundled example was adapted from is published, if it was
     * @param tour whether the example has a tour ({@code GET /rest/v2/examples/{id}/tour})
     */
    public record ExampleDto(String id, String name, String section, String description, String version,
        String mzTabVersion, String focus, String createdBy, URI uri, @Nullable URI originalUri, boolean tour) {

    }

    /** All members are optional; defaults are INFO, 100 and structural validation only. */
    public record ExampleValidationRequest(@Nullable ValidationLevel level, @Nullable Integer maxErrors,
        @Nullable String validationProfile, @Nullable String validationProfileVersion) {

    }

    private final ExampleFileConfig config;
    private final ValidationSessionService sessions;
    private final ExampleFetcher fetcher;
    private final EngineRegistry engines;
    private final TutorialCatalog tutorials;

    public ExamplesApiController(ExampleFileConfig config, ValidationSessionService sessions,
        ExampleFetcher fetcher, EngineRegistry engines, TutorialCatalog tutorials) {
        this.engines = engines;
        this.config = config;
        this.sessions = sessions;
        this.fetcher = fetcher;
        this.tutorials = tutorials;
    }

    /** Whether the example's mzTab-M version line has an engine on this instance; no version means yes. */
    private boolean available(ExampleDto e) {
        if (e.mzTabVersion() == null) {
            return true;
        }
        return MzTabVersions.lineOf(e.mzTabVersion()).filter(engines.lines()::contains).isPresent();
    }

    @Operation(operationId = "listExamples", summary = "Lists the example files of the mzTab-M versions this instance can validate")
    @GetMapping(value = "/examples", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<ExampleDto> listExamples() {
        return config.getExampleFile().stream().map(this::dto).filter(this::available).toList();
    }

    @Operation(operationId = "getExampleContent", summary = "Downloads the file of a bundled example; "
        + "answered whether or not this instance can validate its mzTab-M version")
    @GetMapping(value = "/examples/{id}/content", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public ResponseEntity<String> getExampleContent(@PathVariable("id") @Pattern(regexp = ID_PATTERN) String id) {
        String content = tutorials.content(id).orElseThrow(() -> new UnknownValidationException(UNKNOWN_EXAMPLE));
        return ResponseEntity.ok().contentType(TEXT_PLAIN_UTF8).body(content);
    }

    @Operation(operationId = "getExampleTour", summary = "Gets the tour of a bundled example; "
        + "answered whether or not this instance can validate its mzTab-M version")
    @GetMapping(value = "/examples/{id}/tour", produces = MediaType.APPLICATION_JSON_VALUE)
    public TourDto getExampleTour(@PathVariable("id") @Pattern(regexp = ID_PATTERN) String id) {
        return tutorials.tour(id).orElseThrow(() -> new UnknownValidationException(UNKNOWN_EXAMPLE));
    }

    @Operation(operationId = "validateExample", summary = "Starts a validation of an example file")
    @PostMapping(value = "/examples/{id}/validations", consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ValidationRecord> validateExample(
        @PathVariable("id") @Pattern(regexp = ID_PATTERN) String id,
        @RequestBody(required = false) @Nullable ExampleValidationRequest body) throws IOException {
        ExampleFile file = find(id).filter(f -> available(dto(f)))
            .orElseThrow(() -> new UnknownValidationException(UNKNOWN_EXAMPLE));
        ExampleDto example = dto(file);
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
        Path downloaded;
        try {
            // the configured URI, not the DTO's: a bundled example's DTO points at its content endpoint
            downloaded = fetcher.download(file.getUri());
        } catch (IOException | RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, FETCH_FAILED, e);
        }
        try (InputStream content = Files.newInputStream(downloaded)) {
            // the file name is the example's own, never client-supplied
            record = sessions.submit(example.name(), content, options, null);
        } finally {
            Files.deleteIfExists(downloaded);
        }
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath().path("/rest/v2/validations/{id}")
            .buildAndExpand(record.id()).toUri();
        return ResponseEntity.accepted().location(location).body(record);
    }

    private Optional<ExampleFile> find(String id) {
        return config.getExampleFile().stream().filter(f -> id.equals(f.getName())).findFirst();
    }

    private ExampleDto dto(ExampleFile f) {
        boolean bundled = tutorials.content(f.getName()).isPresent();
        // a bundled example's name matches ID_PATTERN (checked at startup), so it is a safe path segment
        URI uri = bundled ? URI.create("rest/v2/examples/" + f.getName() + "/content") : f.getUri();
        return new ExampleDto(f.getName(), f.getName(), f.getSection(), f.getDescription(), f.getVersion(),
            f.getMzTabVersion(), f.getFocus(), f.getCreatedBy(), uri, f.getOriginalUri(),
            tutorials.tour(f.getName()).isPresent());
    }

    @Nullable
    private static String blankToNull(@Nullable String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
