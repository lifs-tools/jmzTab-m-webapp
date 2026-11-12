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
package org.lifstools.mztab.validator.webapp.engine;

import org.lifstools.mztab.validator.engine.api.EngineError;

/** The engine refused the request (HTTP 400 or 413); {@link #error()} says why. */
public class EngineRequestException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient EngineError error;

    public EngineRequestException(EngineError error) {
        super(error.message());
        this.error = error;
    }

    public EngineError error() {
        return error;
    }
}
