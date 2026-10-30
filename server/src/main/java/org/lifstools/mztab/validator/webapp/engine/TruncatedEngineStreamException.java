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

import java.io.EOFException;
import java.io.UncheckedIOException;

/**
 * An engine's NDJSON validation stream ended before its {@code end} event: the
 * engine died or the connection broke while it answered. An
 * {@link UncheckedIOException}, so {@link EngineClient#validate} reports it as
 * the engine being unavailable ({@link EngineUnavailableException}).
 */
public class TruncatedEngineStreamException extends UncheckedIOException {

    private static final long serialVersionUID = 1L;

    public TruncatedEngineStreamException(String message) {
        super(message, new EOFException(message));
    }
}
