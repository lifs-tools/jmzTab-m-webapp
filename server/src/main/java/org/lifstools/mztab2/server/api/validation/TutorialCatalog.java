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
package org.lifstools.mztab2.server.api.validation;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.lifstools.mztab.validator.webapp.ExampleFileConfig;
import org.lifstools.mztab.validator.webapp.domain.ExampleFile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * The bundled tutorial examples: every configured example whose {@code uri} is {@code classpath:tutorial/<name>},
 * with its step list ({@code tour}) if it has one. All are read and checked at startup; any problem (a missing or
 * unreadable file, a URI outside {@code tutorial/}, a step that does not match its file) stops the startup with a
 * list of all problems, so a broken tour never ships. Remote examples are not part of the catalog.
 */
@Component
public class TutorialCatalog {

    private static final Pattern EXAMPLE_ID = Pattern.compile(ExamplesApiController.ID_PATTERN);

    /** The tour file as authored: the example id comes from the example entry. */
    record TourFile(String title, @Nullable String counterpart, List<TourStepDto> steps) {

    }

    private final Map<String, String> contents = new HashMap<>();
    private final Map<String, TourDto> tours = new HashMap<>();
    private final String root;

    @Autowired
    public TutorialCatalog(ExampleFileConfig config, ObjectMapper mapper) {
        this(config, mapper, ExampleFetcher.TUTORIAL_ROOT);
    }

    /** For tests: {@code root} is the class path directory that {@code classpath:tutorial/} URIs are read from. */
    TutorialCatalog(ExampleFileConfig config, ObjectMapper mapper, String root) {
        this.root = root;
        List<String> problems = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (ExampleFile f : config.getExampleFile()) {
            String id = f.getName();
            if (!names.add(id)) {
                // a remote example with a bundled one's name would be served as the bundled one
                problems.add(id + ": the name is used by another example");
                continue;
            }
            if (f.getOriginalUri() != null && !"https".equals(f.getOriginalUri().getScheme())) {
                problems.add(id + ": originalUri must be an https URL");
            }
            if (!ExampleFetcher.isClasspath(f.getUri())) {
                if (f.getTour() != null) {
                    problems.add(id + ": only a bundled (classpath:" + ExampleFetcher.TUTORIAL_ROOT
                        + "<name>) example can have a tour");
                }
                continue;
            }
            // the name becomes a path segment of the example's content URI: no dot segments
            if (id == null || !EXAMPLE_ID.matcher(id).matches() || id.chars().allMatch(c -> c == '.')) {
                problems.add(id + ": the name of a bundled example must match " + ExamplesApiController.ID_PATTERN
                    + " and not be dots only");
                continue;
            }
            String content;
            try {
                content = utf8(read(f.getUri()));
            } catch (IOException e) {
                problems.add(id + ": " + f.getUri() + ": " + e.getMessage());
                continue;
            }
            contents.put(id, content);
            if (f.getTour() == null) {
                continue;
            }
            TourDto tour;
            try {
                TourFile file = mapper.readerFor(TourFile.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(read(f.getTour()));
                tour = new TourDto(id, file.title(), file.counterpart(),
                    file.steps() == null ? List.of() : List.copyOf(file.steps()));
            } catch (IOException | RuntimeException e) {
                problems.add(id + ": " + f.getTour() + ": " + e.getMessage());
                continue;
            }
            List<String> stepProblems = TourSteps.problems(content.lines().toList(), tour);
            if (stepProblems.isEmpty()) {
                tours.put(id, tour);
            } else {
                stepProblems.forEach(p -> problems.add(id + ": " + f.getTour() + ": " + p));
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("The bundled tutorial examples are invalid:\n- "
                + String.join("\n- ", problems));
        }
    }

    /** The file of a bundled example; empty for an unknown or remote example. */
    public Optional<String> content(String exampleId) {
        return Optional.ofNullable(contents.get(exampleId));
    }

    /** The tour of a bundled example; empty for an unknown or remote example and one without a tour. */
    public Optional<TourDto> tour(String exampleId) {
        return Optional.ofNullable(tours.get(exampleId));
    }

    private byte[] read(URI uri) throws IOException {
        String resource = root + ExampleFetcher.tutorialName(uri);
        try (InputStream in = TutorialCatalog.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("the file is missing");
            }
            return in.readAllBytes();
        }
    }

    private static String utf8(byte[] bytes) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IOException("the file is not UTF-8", e);
        }
    }
}
