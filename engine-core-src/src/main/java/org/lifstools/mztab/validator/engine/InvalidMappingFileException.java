/*
 * Copyright 2026 Leibniz Institut für Analytische Wissenschaften - ISAS e.V..
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

/**
 * Thrown when a CV mapping file is rejected, either because it is not
 * well-formed or because it declares a DOCTYPE, which mapping files never
 * need and which would allow entity resolution.
 */
public class InvalidMappingFileException extends IllegalArgumentException {

    public InvalidMappingFileException(String message, Throwable cause) {
        super(message, cause);
    }
}
