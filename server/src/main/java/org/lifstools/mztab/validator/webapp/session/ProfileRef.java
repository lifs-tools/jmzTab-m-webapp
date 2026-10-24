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
package org.lifstools.mztab.validator.webapp.session;

/**
 * The rules a validation applies: none (structural validation only), a
 * catalog profile by id and version, or a custom mapping uploaded with the
 * file. {@code id} and {@code version} are set only for {@link Kind#CATALOG}.
 */
public record ProfileRef(Kind kind, String id, String version) {

    public enum Kind {
        NONE, CATALOG, CUSTOM
    }

    public static ProfileRef none() {
        return new ProfileRef(Kind.NONE, null, null);
    }

    public static ProfileRef custom() {
        return new ProfileRef(Kind.CUSTOM, null, null);
    }

    public static ProfileRef catalog(String id, String version) {
        return new ProfileRef(Kind.CATALOG, id, version);
    }
}
