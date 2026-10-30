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
import java.util.Optional;
import org.lifstools.mztab.validator.engine.api.EngineInfo;
import org.lifstools.mztab.validator.webapp.domain.AppInfo;
import org.lifstools.mztab.validator.webapp.engine.EngineRegistry;
import org.lifstools.mztab.validator.webapp.engine.EnginesProperties;
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

    /**
     * A validation engine.
     *
     * @param line the mzTab-M version line
     * @param mzTabVersions the declared versions it accepts; empty while it has not been reachable
     * @param jmztabmVersion the jmzTab-m version it runs; null while it has not been reachable
     * @param available whether it was reachable the last time it was asked
     */
    public record EngineDto(String line, List<String> mzTabVersions, @Nullable String jmztabmVersion,
        boolean available) {

    }

    public record AppInfoDto(String version, String buildDate, List<EngineDto> engines, String defaultLine,
        @Nullable String unavailableHintUrl, int retentionDays, long maxUploadBytes, boolean customMappingsEnabled,
        @Nullable AnalyticsDto analytics) {

    }

    private final String version;
    private final String buildDate;
    private final EngineRegistry engines;
    private final String unavailableHintUrl;
    private final int retentionDays;
    private final long maxUploadBytes;
    private final boolean customMappingsEnabled;
    private final AnalyticsDto analytics;

    public AppInfoApiController(AppInfo appInfo, EngineRegistry engines, EnginesProperties engineProperties,
        @Value("${minCleanupAge:7}") int retentionDays,
        @Value("${spring.servlet.multipart.max-file-size:64MB}") DataSize maxUpload,
        @Value("${validation.custom-mappings.enabled:true}") boolean customMappingsEnabled,
        @Value("${matomo.tracker-base:}") String trackerBase) {
        String ga = appInfo.getGaId();
        // an empty ga.id means no analytics
        AnalyticsDto analytics = ga == null || ga.isBlank() ? null : new AnalyticsDto(ga.strip(), trackerBase);
        this.version = appInfo.getVersionNumber();
        this.buildDate = appInfo.getBuildDate();
        this.engines = engines;
        String hint = engineProperties.unavailableHintUrl();
        this.unavailableHintUrl = hint == null || hint.isBlank() ? null : hint.strip();
        this.retentionDays = retentionDays;
        this.maxUploadBytes = maxUpload.toBytes();
        this.customMappingsEnabled = customMappingsEnabled;
        this.analytics = analytics;
    }

    @Operation(operationId = "getAppInfo", summary = "Returns version, validation engines, limits and analytics settings")
    @GetMapping(value = "/app-info", produces = MediaType.APPLICATION_JSON_VALUE)
    public AppInfoDto getAppInfo() {
        List<EngineDto> engineDtos = engines.lastKnownInfo().entrySet().stream().map(e -> {
            Optional<EngineInfo> info = e.getValue();
            return new EngineDto(e.getKey(), info.map(EngineInfo::mzTabVersions).orElse(List.of()),
                info.map(EngineInfo::jmztabmVersion).orElse(null), info.isPresent());
        }).toList();
        return new AppInfoDto(version, buildDate, engineDtos, engines.defaultLine(), unavailableHintUrl,
            retentionDays, maxUploadBytes, customMappingsEnabled, analytics);
    }
}
