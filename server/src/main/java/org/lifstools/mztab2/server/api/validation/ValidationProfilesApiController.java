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
import java.util.List;
import org.lifstools.mztab.validator.webapp.validationprofile.ValidationProfile;
import org.lifstools.mztab.validator.webapp.validationprofile.ValidationProfileCatalog;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/rest/v2")
@Tag(name = "validation-profiles")
public class ValidationProfilesApiController {

    private final ValidationProfileCatalog catalog;

    public ValidationProfilesApiController(ValidationProfileCatalog catalog) {
        this.catalog = catalog;
    }

    @Operation(operationId = "listValidationProfiles", summary = "Lists the available validation profiles (latest version of each)")
    @GetMapping(value = "/validation-profiles", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<ValidationProfile> listValidationProfiles() {
        return catalog.latest();
    }
}
