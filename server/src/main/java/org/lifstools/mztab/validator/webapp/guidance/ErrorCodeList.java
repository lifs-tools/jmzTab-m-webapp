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
package org.lifstools.mztab.validator.webapp.guidance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The codes of an engine's error catalogue, exported at build time to
 * {@code guidance/error-codes-<line>.json}.
 */
public final class ErrorCodeList {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, Set<String>> CACHE = new ConcurrentHashMap<>();

    private ErrorCodeList() {
    }

    /**
     * @param line the mzTab-M line, {@code "2.0"} or {@code "2.1"}
     * @return the type codes the line's engine knows, e.g. {@code "Error-1012"}
     */
    public static Set<String> codes(String line) {
        return CACHE.computeIfAbsent(line, ErrorCodeList::load);
    }

    /**
     * @param line the mzTab-M line
     * @return the numbers of the codes, ignoring the level
     */
    public static Set<Integer> numbers(String line) {
        Set<Integer> numbers = new TreeSet<>();
        for (String c : codes(line)) {
            numbers.add(GuidanceCatalog.numericPart(c));
        }
        return numbers;
    }

    private static Set<String> load(String line) {
        if (!line.matches("\\d+\\.\\d+")) {
            throw new IllegalArgumentException("Not an mzTab-M line: " + line);
        }
        String path = "/guidance/error-codes-" + line + ".json";
        try (InputStream in = ErrorCodeList.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalArgumentException("No error code list for line " + line);
            }
            Set<String> codes = new TreeSet<>();
            for (JsonNode n : MAPPER.readTree(in)) {
                codes.add(n.get("level").asText() + "-" + n.get("code").asInt());
            }
            return Set.copyOf(codes);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + path, e);
        }
    }
}
