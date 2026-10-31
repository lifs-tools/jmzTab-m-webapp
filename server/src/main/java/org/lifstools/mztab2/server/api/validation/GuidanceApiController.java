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
import java.time.Duration;
import java.util.List;
import org.lifstools.mztab.validator.webapp.guidance.GuidanceCatalog;
import org.lifstools.mztab.validator.webapp.guidance.GuidanceEntry;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The public list of the problems the webapp knows how to explain. */
@RestController
@RequestMapping("/rest/v2")
@Tag(name = "guidance")
public class GuidanceApiController {

    private final GuidanceCatalog catalog;

    public GuidanceApiController(GuidanceCatalog catalog) {
        this.catalog = catalog;
    }

    @Operation(operationId = "listGuidance", summary = "Lists the problems that come with an explanation and a fix")
    @GetMapping(value = "/guidance", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<GuidanceEntry>> listGuidance() {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
            .body(catalog.entries());
    }
}
