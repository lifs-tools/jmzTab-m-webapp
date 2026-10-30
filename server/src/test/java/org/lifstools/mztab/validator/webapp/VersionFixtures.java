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
package org.lifstools.mztab.validator.webapp;

import java.nio.charset.StandardCharsets;

/** Uploads whose declared version decides the engine, shared by the server tests. */
public final class VersionFixtures {

    /**
     * As engine-api's {@code version-detection/tsv-latin1.mztab}: declares
     * {@code 2.1.0-M}, with one ISO-8859-1 byte (µ, 0xB5) in a later MTD line.
     */
    public static final byte[] LATIN1_21 = ("COM\tgenerated, encoded as ISO-8859-1\nMTD\tmzTab-version\t2.1.0-M\n"
        + "MTD\tmzTab-ID\tA\nMTD\tdescription\tinjection volume 5 µL\nSMH\tx\n")
        .getBytes(StandardCharsets.ISO_8859_1);

    private VersionFixtures() {
    }
}
