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
import java.util.List;
import java.util.Optional;
import org.lifstools.mztab.validator.engine.api.MzTabVersions;
import org.lifstools.mztab.validator.webapp.engine.EngineRegistry;
import org.lifstools.mztab.validator.webapp.validationprofile.ValidationProfile;
import org.lifstools.mztab.validator.webapp.validationprofile.ValidationProfileCatalog;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/rest/v2")
@Tag(name = "validation-profiles")
public class ValidationProfilesApiController {

    static final String ID_PATTERN = "^[a-z0-9][a-z0-9.-]{0,63}$";
    static final String VERSION_PATTERN = "^[0-9]{1,4}\\.[0-9]{1,4}\\.[0-9]{1,4}$";
    /** A line ({@code 2.1}) or a version ({@code 2.1.0-M}). */
    static final String MZTAB_VERSION_PATTERN = "^[0-9]{1,4}\\.[0-9]{1,4}(\\.[0-9]{1,4}(-[Mm])?)?$";

    private final ValidationProfileCatalog catalog;
    private final EngineRegistry engines;

    public ValidationProfilesApiController(ValidationProfileCatalog catalog, EngineRegistry engines) {
        this.catalog = catalog;
        this.engines = engines;
    }

    @Operation(operationId = "listValidationProfiles", summary = "Lists the available validation profiles (latest version of each) "
        + "of the mzTab-M versions this instance can validate")
    @GetMapping(value = "/validation-profiles", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<ValidationProfile> listValidationProfiles(
        @Pattern(regexp = MZTAB_VERSION_PATTERN) @RequestParam(name = "mzTabVersion", required = false) @Nullable String mzTabVersion) {
        List<ValidationProfile> available = catalog.forLines(engines.lines());
        if (mzTabVersion == null) {
            return available;
        }
        Optional<String> line = MzTabVersions.lineOf(mzTabVersion);
        return available.stream().filter(p -> line.isPresent() && p.mzTabVersions() != null
            && p.mzTabVersions().stream().map(MzTabVersions::lineOf).anyMatch(l -> l.equals(line))).toList();
    }

    @Operation(operationId = "getValidationProfileMapping", summary = "Downloads the CV mapping file of a validation profile version")
    @GetMapping(value = "/validation-profiles/{id}/{version}/mapping", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<byte[]> getValidationProfileMapping(@PathVariable("id") @Pattern(regexp = ID_PATTERN) String id,
        @PathVariable("version") @Pattern(regexp = VERSION_PATTERN) String version) throws IOException {
        ValidationProfile profile = catalog.find(id, version)
            .orElseThrow(() -> new UnknownValidationException("Unknown validation profile."));
        byte[] xml;
        try (InputStream in = catalog.mappingUrl(profile).openStream()) {
            xml = in.readAllBytes();
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename(profile.id() + "-" + profile.version() + ".xml").build().toString())
            .body(xml);
    }
}
