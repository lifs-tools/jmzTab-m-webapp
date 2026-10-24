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
import org.lifstools.mztab.validator.webapp.domain.AppInfo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.lang.Nullable;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/rest/v2")
@Tag(name = "app-info")
public class AppInfoApiController {

    public record AnalyticsDto(String siteId, String trackerBase) {

    }

    public record AppInfoDto(String version, String buildDate, String jmztabmVersion, int retentionDays,
        long maxUploadBytes, boolean customMappingsEnabled, @Nullable AnalyticsDto analytics) {

    }

    private final AppInfoDto info;

    public AppInfoApiController(AppInfo appInfo, @Value("${minCleanupAge:7}") int retentionDays,
        @Value("${spring.servlet.multipart.max-file-size:64MB}") DataSize maxUpload,
        @Value("${validation.custom-mappings.enabled:true}") boolean customMappingsEnabled,
        @Value("${matomo.tracker-base:}") String trackerBase) {
        String ga = appInfo.getGaId();
        // an empty ga.id means no analytics
        AnalyticsDto analytics = ga == null || ga.isBlank() ? null : new AnalyticsDto(ga.strip(), trackerBase);
        this.info = new AppInfoDto(appInfo.getVersionNumber(), appInfo.getBuildDate(),
            appInfo.getJmztabmVersionNumber(), retentionDays, maxUpload.toBytes(), customMappingsEnabled, analytics);
    }

    @Operation(operationId = "getAppInfo", summary = "Returns version, limits and analytics settings")
    @GetMapping(value = "/app-info", produces = MediaType.APPLICATION_JSON_VALUE)
    public AppInfoDto getAppInfo() {
        return info;
    }
}
