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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.lifstools.mztab.validator.engine.api.ValidationMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ResourceLoader;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * The guidance entries curated by the LIFS team, read once from
 * {@code guidance/catalog.json}. A message is matched to an entry by the number
 * of its type code: CV-rule messages carry the rule's requirement level, so the
 * level of a code can differ from the one in the engine's list.
 */
@Component
public class GuidanceCatalog {

    private static final Pattern TYPE_CODE = Pattern.compile("^\\s*\\[([^\\]]+)\\]");

    private final List<GuidanceEntry> entries;
    private final List<GuidanceEntry> ordered;
    private final java.util.Map<String, Pattern> patterns = new java.util.HashMap<>();

    public GuidanceCatalog(ObjectMapper objectMapper, ResourceLoader resourceLoader,
        @Value("${guidance.catalog:classpath:/guidance/catalog.json}") String location) {
        try (InputStream in = resourceLoader.getResource(location).getInputStream()) {
            this.entries = List.copyOf(objectMapper.readValue(in, new TypeReference<List<GuidanceEntry>>() {
            }));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the guidance catalog " + location, e);
        }
        this.ordered = validate(entries);
    }

    /** For tests and callers that use the shipped catalog. */
    public GuidanceCatalog(ObjectMapper objectMapper, ResourceLoader resourceLoader) {
        this(objectMapper, resourceLoader, "classpath:/guidance/catalog.json");
    }

    private List<GuidanceEntry> validate(List<GuidanceEntry> list) {
        Set<String> ids = new HashSet<>();
        for (GuidanceEntry e : list) {
            if (!ids.add(e.id())) {
                throw new IllegalStateException("Duplicate guidance entry id " + e.id());
            }
            for (String code : e.codes()) {
                numericPart(code);
            }
            try {
                if (e.messagePattern() != null) {
                    patterns.put(e.id(), Pattern.compile(e.messagePattern()));
                }
                for (ToolVariant t : e.tools()) {
                    Pattern.compile(t.software(), Pattern.CASE_INSENSITIVE);
                }
            } catch (PatternSyntaxException ex) {
                throw new IllegalStateException("Invalid regex in guidance entry " + e.id(), ex);
            }
        }
        List<GuidanceEntry> o = new ArrayList<>();
        list.stream().filter(e -> e.messagePattern() != null).forEach(o::add);
        list.stream().filter(e -> e.messagePattern() == null).forEach(o::add);
        return List.copyOf(o);
    }

    public List<GuidanceEntry> entries() {
        return entries;
    }

    /**
     * @param m a validation message
     * @param line the mzTab-M line of the file, e.g. {@code "2.0"}, or null if unknown
     * @return the first matching entry; entries with a message pattern are tried first
     */
    public Optional<GuidanceEntry> match(ValidationMessage m, @Nullable String line) {
        String typeCode = typeCodeOf(m);
        if (typeCode.isEmpty()) {
            return Optional.empty();
        }
        int number;
        try {
            number = numericPart(typeCode);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        String text = m.getMessage() == null ? "" : m.getMessage();
        for (GuidanceEntry e : ordered) {
            if (e.lines() != null && line != null && !e.lines().contains(line)) {
                continue;
            }
            if (e.codes().stream().noneMatch(c -> numericPart(c) == number)) {
                continue;
            }
            Pattern p = patterns.get(e.id());
            if (p != null && !p.matcher(text).find()) {
                continue;
            }
            return Optional.of(e);
        }
        return Optional.empty();
    }

    /**
     * @param m a validation message
     * @return the content of the leading bracket of its code, e.g. {@code "Error-1012"}, or empty
     */
    public static String typeCodeOf(ValidationMessage m) {
        if (m.getCode() == null) {
            return "";
        }
        Matcher matcher = TYPE_CODE.matcher(m.getCode());
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    /**
     * @param typeCode e.g. {@code "Warn-3005"}
     * @return the number after the last dash
     * @throws IllegalArgumentException if there is none
     */
    public static int numericPart(String typeCode) {
        try {
            return Integer.parseInt(typeCode.substring(typeCode.lastIndexOf('-') + 1).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Not a type code: " + typeCode, e);
        }
    }
}
