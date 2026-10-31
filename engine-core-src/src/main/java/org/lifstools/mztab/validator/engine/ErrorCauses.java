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
package org.lifstools.mztab.validator.engine;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import uk.ac.ebi.pride.jmztab2.utils.errors.MZTabErrorType;
import uk.ac.ebi.pride.jmztab2.utils.errors.MZTabErrorTypeMap;

/**
 * The cause text jmzTab-m publishes for each of its error codes. The engines
 * attach it to the messages they report, so the webapp can explain a message
 * without any jmzTab-m dependency.
 */
final class ErrorCauses {

    private static final Pattern TYPE_CODE = Pattern.compile("^\\s*\\[(?:Error|Warn|Info)-(\\d+)\\]");

    private static final MZTabErrorTypeMap TYPES = new MZTabErrorTypeMap();

    private ErrorCauses() {
    }

    /**
     * @param code a message's code, as jmzTab-m writes it, e.g.
     * {@code [Error-1000] line 5: ...}
     * @return the cause of the code's error type; empty if the code carries no
     * known type code or the type has no cause
     */
    static Optional<String> causeOf(String code) {
        if (code == null) {
            return Optional.empty();
        }
        Matcher m = TYPE_CODE.matcher(code);
        if (!m.find()) {
            return Optional.empty();
        }
        MZTabErrorType type;
        try {
            type = TYPES.getType(Integer.parseInt(m.group(1)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        if (type == null || type.getCause() == null) {
            return Optional.empty();
        }
        String cause = type.getCause().replaceFirst("^[=\\s]+", "").strip();
        return cause.isEmpty() ? Optional.empty() : Optional.of(cause);
    }
}
