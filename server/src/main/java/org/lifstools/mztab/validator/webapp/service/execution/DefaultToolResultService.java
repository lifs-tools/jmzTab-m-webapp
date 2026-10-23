/*
 * Copyright 2018 nilshoffmann.
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
package org.lifstools.mztab.validator.webapp.service.execution;

import org.lifstools.mztab.validator.webapp.domain.ToolResult;
import org.lifstools.mztab.validator.webapp.service.ToolResultService;
import org.lifstools.mztab.validator.webapp.service.ValidationService;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 *
 * @author nilshoffmann
 */
@Service
public class DefaultToolResultService implements ToolResultService {

    private final ConcurrentHashMap<UUID, ToolResult> sessionToToolResultMap = new ConcurrentHashMap<>();

    @Override
    public ToolResult getOrCreateResultFor(UUID sessionId) {
        if (sessionToToolResultMap.containsKey(sessionId)) {
            return sessionToToolResultMap.get(sessionId);
        }
        ToolResult result = new ToolResult(null,
            ValidationService.Status.UNINITIALIZED);
        return result;
    }

    @Override
    public void addResultFor(UUID sessionId, ToolResult result) {
        sessionToToolResultMap.put(sessionId, result);
    }

    @Override
    public void deleteResultFor(UUID sessionId) {
        this.sessionToToolResultMap.remove(sessionId);
    }

    @Override
    public void deleteAllResults() {
        this.sessionToToolResultMap.clear();
    }
}
