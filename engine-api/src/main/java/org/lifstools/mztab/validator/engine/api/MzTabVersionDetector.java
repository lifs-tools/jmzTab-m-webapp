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
package org.lifstools.mztab.validator.engine.api;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Finds the declared {@code mzTab-version} of an mzTab-M file, as tab separated
 * text or as JSON, without reading more of the file than necessary.
 * <p>
 * Tab separated text is decoded as UTF-8 with malformed bytes replaced, as
 * the SPA's {@code TextDecoder} and jmzTab-m read it: the version line is
 * ASCII, so a file with, e.g., one ISO-8859-1 byte elsewhere still declares
 * its version, and goes to the engine of that version's line.
 */
public final class MzTabVersionDetector {

    private static final String VERSION_PREFIX = "MTD\tmzTab-version\t";
    private static final String BOM = "﻿";
    private static final JsonFactory JSON = new JsonFactory();

    private MzTabVersionDetector() {
    }

    /**
     * Detects the version of a tab separated or JSON file.
     *
     * @param file the file to inspect
     * @return the declared version, or empty if there is none
     * @throws IOException if the file cannot be read
     */
    public static Optional<String> detect(Path file) throws IOException {
        return isJson(file) ? detectJson(file) : detectTsv(file);
    }

    /**
     * Returns the value of the first {@code MTD mzTab-version} line, reading
     * only the leading MTD/COM block of the file. A UTF-8 byte order mark and
     * CRLF line ends are accepted; bytes that are not UTF-8 are replaced.
     *
     * @param file the file to inspect
     * @return the version, or empty if there is none
     * @throws IOException if the file cannot be read
     */
    public static Optional<String> detectTsv(Path file) throws IOException {
        try (BufferedReader reader = lenientUtf8(file)) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (first && line.startsWith(BOM)) {
                    line = line.substring(BOM.length());
                }
                first = false;
                if (line.startsWith(VERSION_PREFIX)) {
                    String value = line.substring(VERSION_PREFIX.length()).trim();
                    int tab = value.indexOf('\t');
                    return Optional.of(tab >= 0 ? value.substring(0, tab).trim() : value);
                }
                if (!isMetadataOrComment(line) && !line.isBlank()) {
                    break;
                }
            }
        }
        return Optional.empty();
    }

    /** An {@code MTD} or {@code COM} line, also one without a tab (an empty comment). */
    private static boolean isMetadataOrComment(String line) {
        if (line.startsWith("MTD\t") || line.startsWith("COM\t")) {
            return true;
        }
        String bare = line.stripTrailing();
        return bare.equals("MTD") || bare.equals("COM");
    }

    /**
     * Streams the JSON file and returns the {@code mzTab-version} field of its
     * top-level {@code metadata} object, as Jackson binds it into the mzTab-M
     * model: a scalar value (string, number, boolean) as its text, e.g.
     * {@code 2.1} as {@code "2.1"}; {@code null}, an object or an array as no
     * version. Of duplicate keys the last one wins, as in Jackson: a later
     * {@code metadata} object replaces an earlier one as a whole, and a later
     * {@code mzTab-version} an earlier one. Other subtrees are skipped without
     * being built, so the position of {@code metadata} does not matter for
     * memory use; the whole file is read, because a later duplicate decides.
     *
     * @param file the file to inspect
     * @return the version, or empty if there is none
     * @throws IOException if the file cannot be read or is not well formed
     */
    public static Optional<String> detectJson(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file); JsonParser p = JSON.createParser(in)) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                return Optional.empty();
            }
            Optional<String> version = Optional.empty();
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String name = p.currentName();
                JsonToken value = p.nextToken();
                if (!"metadata".equals(name)) {
                    p.skipChildren();
                    continue;
                }
                version = value == JsonToken.START_OBJECT ? metadataVersion(p) : Optional.empty();
                p.skipChildren();
            }
            return version;
        }
    }

    /** The last {@code mzTab-version} of the metadata object the parser is at; leaves the parser at its end. */
    private static Optional<String> metadataVersion(JsonParser p) throws IOException {
        Optional<String> version = Optional.empty();
        while (p.nextToken() == JsonToken.FIELD_NAME) {
            String key = p.currentName();
            JsonToken v = p.nextToken();
            if ("mzTab-version".equals(key)) {
                version = v.isScalarValue() && v != JsonToken.VALUE_NULL ? Optional.of(p.getText()) : Optional.empty();
            }
            p.skipChildren();
        }
        return version;
    }

    /**
     * @param file the file to inspect
     * @return true if the first non-blank character, ignoring a byte order mark, is <code>{</code>; bytes that
     * are not UTF-8 are replaced, and a replaced first character is not <code>{</code>
     * @throws IOException if the file cannot be read
     */
    public static boolean isJson(Path file) throws IOException {
        try (BufferedReader reader = lenientUtf8(file)) {
            int c;
            while ((c = reader.read()) != -1) {
                if (c == '﻿' || Character.isWhitespace(c)) {
                    continue;
                }
                return c == '{';
            }
            return false;
        }
    }

    /** UTF-8 with malformed and unmappable input replaced, never a {@code MalformedInputException}. */
    private static BufferedReader lenientUtf8(Path file) throws IOException {
        return new BufferedReader(new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)));
    }
}
