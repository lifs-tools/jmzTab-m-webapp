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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.lifstools.mztab.validator.engine.api.ValidationLocation;
import org.lifstools.mztab.validator.engine.api.ValidationMessage;

/**
 * jmzTab-m reports Info-2057 (a field or column the declared profile has no section for) with line number -1 and
 * no location, so a client cannot show the element. The message quotes the name before {@code is present}; if
 * that name is exactly one metadata key or exactly one table header column of the file, this gives the message
 * that line and a location in the form jmzTab-m uses for located messages. A name that is no key or column but
 * the group of indexed keys ({@code database} for {@code database[1]}, {@code database[1]-prefix}, ...) gets the
 * group's first line and a location naming the group without a key. A name that is not found, or found more than
 * once, leaves the message as it is. Only the first header line of each table counts, as for the quick fix that
 * removes the column.
 */
final class MismatchLocator {

    private static final Pattern CODE = Pattern.compile("^\\s*\\[[A-Za-z]+-(\\d+)\\]");
    private static final Pattern NAME = Pattern.compile("^\\s*\"(.+?)\" is present");
    private static final Pattern LINE_IN_CODE = Pattern.compile("^(\\s*\\[[A-Za-z]+-\\d+\\] line )-?\\d+");
    private static final int PROFILE_MISMATCH = 2057;

    private MismatchLocator() {
    }

    /** A place of a name in the file: a line, and for a column the header section. */
    private record Place(long line, ValidationLocation.Section section, String column) {

    }

    /** Locates the messages that need it; reads the file only if there is one. */
    static void locate(List<ValidationMessage> messages, Path file) throws IOException {
        if (candidates(messages).isEmpty()) {
            return;
        }
        try (Reader in = new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8)) {
            locate(messages, in);
        }
    }

    /** Locates the messages that need it in the file's text. */
    static void locate(List<ValidationMessage> messages, String text) {
        try {
            locate(messages, new StringReader(text));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<ValidationMessage> candidates(List<ValidationMessage> messages) {
        List<ValidationMessage> found = new ArrayList<>();
        for (ValidationMessage m : messages) {
            if (m.getLocation() == null && isMismatch(m) && nameOf(m) != null) {
                found.add(m);
            }
        }
        return found;
    }

    private static boolean isMismatch(ValidationMessage m) {
        Matcher code = CODE.matcher(m.getCode() == null ? "" : m.getCode());
        return code.find() && Integer.parseInt(code.group(1)) == PROFILE_MISMATCH;
    }

    private static String nameOf(ValidationMessage m) {
        Matcher name = NAME.matcher(m.getMessage() == null ? "" : m.getMessage());
        return name.find() ? name.group(1) : null;
    }

    private static void locate(List<ValidationMessage> messages, Reader text) throws IOException {
        List<ValidationMessage> todo = candidates(messages);
        if (todo.isEmpty()) {
            return;
        }
        List<String> names = todo.stream().map(MismatchLocator::nameOf).distinct().toList();
        Map<String, List<Place>> places = new HashMap<>();
        Map<String, Long> groups = new HashMap<>();
        Set<ValidationLocation.Section> headersSeen = EnumSet.noneOf(ValidationLocation.Section.class);
        BufferedReader reader = new BufferedReader(text);
        long number = 0;
        for (String line = reader.readLine(); line != null; line = reader.readLine()) {
            number++;
            if (line.startsWith("MTD\t")) {
                String key = line.split("\t", 3)[1];
                if (names.contains(key)) {
                    places.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(new Place(number, ValidationLocation.Section.MTD, null));
                }
                int bracket = key.indexOf('[');
                if (bracket > 0 && names.contains(key.substring(0, bracket))) {
                    groups.putIfAbsent(key.substring(0, bracket), number);
                }
            } else if (line.startsWith("SMH\t") || line.startsWith("SFH\t") || line.startsWith("SEH\t")) {
                ValidationLocation.Section section = ValidationLocation.Section.valueOf(line.substring(0, 3));
                if (!headersSeen.add(section)) {
                    continue;
                }
                String[] cells = line.split("\t", -1);
                for (int i = 1; i < cells.length; i++) {
                    if (names.contains(cells[i])) {
                        places.computeIfAbsent(cells[i], k -> new ArrayList<>())
                            .add(new Place(number, section, cells[i]));
                    }
                }
            }
        }
        for (ValidationMessage m : todo) {
            String name = nameOf(m);
            List<Place> found = places.getOrDefault(name, List.of());
            if (found.size() == 1) {
                apply(m, name, found.get(0));
            } else if (found.isEmpty() && groups.containsKey(name)) {
                applyGroup(m, name, groups.get(name));
            }
        }
    }

    private static void apply(ValidationMessage m, String name, Place place) {
        ValidationLocation location = new ValidationLocation();
        location.setSection(place.section());
        if (place.section() == ValidationLocation.Section.MTD) {
            location.setMetadataKey(name);
            int bracket = name.indexOf('[');
            if (bracket > 0) {
                location.setMetadataGroup(name.substring(0, bracket));
            }
        } else {
            location.setColumn(place.column());
        }
        setPlace(m, location, place.line());
    }

    /** A group of indexed keys: the location names the group, without a key, at the group's first line. */
    private static void applyGroup(ValidationMessage m, String group, long firstLine) {
        ValidationLocation location = new ValidationLocation();
        location.setSection(ValidationLocation.Section.MTD);
        location.setMetadataGroup(group);
        setPlace(m, location, firstLine);
    }

    private static void setPlace(ValidationMessage m, ValidationLocation location, long line) {
        m.setLocation(location);
        m.setLineNumber(line);
        if (m.getCode() != null) {
            m.setCode(LINE_IN_CODE.matcher(m.getCode()).replaceFirst("$1" + line));
        }
    }
}
