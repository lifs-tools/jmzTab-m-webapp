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
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.lifstools.mztab.validator.webapp.domain.ValidationLevel;
import org.lifstools.mztab.validator.webapp.sections.MetadataGroup;
import org.lifstools.mztab.validator.webapp.sections.MetadataPage;
import org.lifstools.mztab.validator.webapp.sections.Section;
import org.lifstools.mztab.validator.webapp.sections.SectionReader;
import org.lifstools.mztab.validator.webapp.sections.TablePage;
import org.lifstools.mztab.validator.webapp.sections.TableQuery;
import org.lifstools.mztab.validator.webapp.session.ValidationOptions;
import org.lifstools.mztab.validator.webapp.session.ValidationRecord;
import org.lifstools.mztab.validator.webapp.session.ValidationSessionService;
import org.lifstools.mztab2.model.ValidationMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/rest/v2")
@Tag(name = "validations")
public class ValidationsApiController {

    private static final int MAX_ERRORS_LIMIT = 500;

    private final ValidationSessionService sessions;
    private final SectionReader sections;
    private final boolean customMappingsEnabled;

    public ValidationsApiController(ValidationSessionService sessions, SectionReader sections,
        @Value("${validation.custom-mappings.enabled:true}") boolean customMappingsEnabled) {
        this.sessions = sessions;
        this.sections = sections;
        this.customMappingsEnabled = customMappingsEnabled;
    }

    @Operation(operationId = "createValidation", summary = "Starts a validation of an uploaded mzTab-M file")
    @PostMapping(value = "/validations", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ValidationRecord> createValidation(
        @RequestPart("file") MultipartFile file,
        @RequestParam(value = "level", defaultValue = "INFO") ValidationLevel level,
        @RequestParam(value = "maxErrors", defaultValue = "100") int maxErrors,
        @RequestParam(value = "validationProfile", required = false) @Nullable String validationProfile,
        @RequestParam(value = "validationProfileVersion", required = false) @Nullable String validationProfileVersion,
        @RequestPart(value = "mappingFile", required = false) @Nullable MultipartFile mappingFile) throws IOException {
        if (maxErrors < 1 || maxErrors > MAX_ERRORS_LIMIT) {
            throw new IllegalArgumentException("maxErrors must be between 1 and " + MAX_ERRORS_LIMIT + ".");
        }
        boolean hasMapping = mappingFile != null && !mappingFile.isEmpty();
        if (hasMapping && !customMappingsEnabled) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Custom mapping files are disabled.");
        }
        ValidationOptions options = new ValidationOptions(level, maxErrors, blankToNull(validationProfile),
            blankToNull(validationProfileVersion));
        ValidationRecord record;
        try (InputStream content = file.getInputStream()) {
            if (hasMapping) {
                try (InputStream mapping = mappingFile.getInputStream()) {
                    record = sessions.submit(baseName(file.getOriginalFilename()), content, options, mapping);
                }
            } else {
                record = sessions.submit(baseName(file.getOriginalFilename()), content, options, null);
            }
        }
        URI location = org.springframework.web.servlet.support.ServletUriComponentsBuilder.fromCurrentRequestUri()
            .path("/{id}").buildAndExpand(record.id()).toUri();
        return ResponseEntity.accepted().location(location).body(record);
    }

    @Operation(operationId = "getValidation", summary = "Returns the state and result of a validation")
    @GetMapping(value = "/validations/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ValidationRecord getValidation(@PathVariable("id") String id) {
        return record(id);
    }

    @Operation(operationId = "deleteValidation", summary = "Deletes a validation and its stored data")
    @DeleteMapping("/validations/{id}")
    public ResponseEntity<Void> deleteValidation(@PathVariable("id") String id) {
        if (!sessions.delete(parse(id))) {
            throw new UnknownValidationException();
        }
        return ResponseEntity.noContent().build();
    }

    @Operation(operationId = "getValidationReport", summary = "Downloads the validation messages as CSV or JSON")
    @GetMapping("/validations/{id}/report")
    public ResponseEntity<?> getValidationReport(@PathVariable("id") String id,
        @RequestParam(value = "format", defaultValue = "csv") String format) {
        ValidationRecord record = record(id);
        String f = format.toLowerCase(Locale.ROOT);
        if (!f.equals("csv") && !f.equals("json")) {
            throw new IllegalArgumentException("format must be csv or json.");
        }
        HttpHeaders headers = new HttpHeaders();
        String download = record.fileName() + "-validation." + f;
        boolean ascii = StandardCharsets.US_ASCII.newEncoder().canEncode(download);
        headers.setContentDisposition(ContentDisposition.attachment()
            .filename(download, ascii ? null : StandardCharsets.UTF_8).build());
        if (f.equals("json")) {
            return ResponseEntity.ok().headers(headers).contentType(MediaType.APPLICATION_JSON)
                .body(record.messages());
        }
        return ResponseEntity.ok().headers(headers).contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
            .body(csv(record.messages()));
    }

    @Operation(operationId = "listMetadataGroups", summary = "Lists the metadata groups of a validated file")
    @GetMapping(value = "/validations/{id}/metadata", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<MetadataGroup> listMetadataGroups(@PathVariable("id") String id) {
        UUID uuid = record(id).id();
        return sections.metadataGroups(uuid);
    }

    @Operation(operationId = "getMetadataPage", summary = "Returns one page of a metadata group")
    @GetMapping(value = "/validations/{id}/metadata/{group}", produces = MediaType.APPLICATION_JSON_VALUE)
    public MetadataPage getMetadataPage(@PathVariable("id") String id, @PathVariable("group") String group,
        @RequestParam(value = "page", defaultValue = "0") int page,
        @RequestParam(value = "size", defaultValue = "50") int size) {
        UUID uuid = record(id).id();
        return sections.metadata(uuid, group, page, size).orElseThrow(() -> new UnknownValidationException("No such metadata group."));
    }

    @Parameter(name = "filter", in = ParameterIn.QUERY,
        description = "COLUMN:text, repeatable; the column is the first ':'-prefix that is an exact column name, the rest is the text (not split at commas)",
        array = @ArraySchema(schema = @Schema(type = "string")))
    @Operation(operationId = "getTablePage", summary = "Returns one page of the SML, SMF or SME table")
    @GetMapping(value = "/validations/{id}/tables/{section}", produces = MediaType.APPLICATION_JSON_VALUE)
    public TablePage getTablePage(@PathVariable("id") String id, @PathVariable("section") String section,
        @RequestParam(value = "page", defaultValue = "0") int page,
        @RequestParam(value = "size", defaultValue = "50") int size,
        @RequestParam(value = "sort", required = false) @Nullable String sort,
        @RequestParam(value = "descending", defaultValue = "false") boolean descending,
        @RequestParam(value = "columns", required = false) @Nullable List<String> columns,
        @Parameter(hidden = true) HttpServletRequest request) {
        // read raw: binding to a List would split a single value at commas
        String[] filter = request.getParameterValues("filter");
        UUID uuid = record(id).id();
        Section s;
        try {
            s = Section.valueOf(section.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new UnknownValidationException("Unknown table section.");
        }
        List<String> rawFilters = new ArrayList<>();
        if (filter != null) {
            for (String raw : filter) {
                if (raw.indexOf(':') < 1) {
                    throw new IllegalArgumentException("filter must have the form COLUMN:text.");
                }
                rawFilters.add(raw);
            }
        }
        // the column is resolved against the table header: its name may itself contain ':'
        TableQuery query = new TableQuery(page, size, blankToNull(sort), descending, List.of(),
            columns == null ? List.of() : columns, rawFilters);
        return sections.table(uuid, s, query).orElseThrow(() -> new UnknownValidationException("No such table."));
    }

    private ValidationRecord record(String id) {
        return sessions.find(parse(id)).orElseThrow(UnknownValidationException::new);
    }

    private static UUID parse(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new UnknownValidationException();
        }
    }

    static String baseName(@Nullable String name) {
        if (name == null) {
            return "upload.mzTab";
        }
        String n = name.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).replaceAll("\\p{Cntrl}", "").strip();
        if (n.isEmpty() || n.equals(".") || n.equals("..")) {
            return "upload.mzTab";
        }
        return n;
    }

    @Nullable
    private static String blankToNull(@Nullable String s) {
        return s == null || s.isBlank() ? null : s;
    }

    static String csv(List<ValidationMessage> messages) {
        StringBuilder sb = new StringBuilder("level,line,category,code,message\r\n");
        for (ValidationMessage m : messages) {
            sb.append(field(m.getMessageType() == null ? "" : m.getMessageType().getValue())).append(',')
                .append(m.getLineNumber() == null ? "" : m.getLineNumber().toString()).append(',')
                .append(field(m.getCategory() == null ? "" : m.getCategory().name())).append(',')
                .append(m.getCode() == null ? "" : m.getCode().toString()).append(',')
                .append(field(m.getMessage())).append("\r\n");
        }
        return sb.toString();
    }

    private static String field(@Nullable String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        // spreadsheet formula injection: the text comes from the uploaded file
        if ("=+-@\t\r".indexOf(value.charAt(0)) >= 0) {
            value = "'" + value;
        }
        if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }
}
