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

import com.fasterxml.jackson.core.JsonProcessingException;
import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.file.Path;
import java.util.Optional;
import org.lifstools.mztab.validator.engine.api.MzTabVersionDetector;

/**
 * The declared {@code mzTab-version} of a file, which decides its engine
 * ({@link EngineRegistry#route}). A file that is not UTF-8 text, or JSON that
 * is not well formed up to its version, declares none here: it goes to the
 * default line, whose engine reports what is wrong with it.
 */
public final class DeclaredVersions {

    private DeclaredVersions() {
    }

    /** Of a tab separated or JSON file. */
    public static Optional<String> of(Path file) throws IOException {
        try {
            return MzTabVersionDetector.detect(file).filter(v -> !v.isBlank());
        } catch (MalformedInputException | JsonProcessingException e) {
            return Optional.empty();
        }
    }

    /** Of a file that is JSON. */
    public static Optional<String> ofJson(Path file) throws IOException {
        try {
            return MzTabVersionDetector.detectJson(file).filter(v -> !v.isBlank());
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }

    /** Of a file that is tab separated. */
    public static Optional<String> ofTsv(Path file) throws IOException {
        try {
            return MzTabVersionDetector.detectTsv(file).filter(v -> !v.isBlank());
        } catch (MalformedInputException e) {
            return Optional.empty();
        }
    }
}
