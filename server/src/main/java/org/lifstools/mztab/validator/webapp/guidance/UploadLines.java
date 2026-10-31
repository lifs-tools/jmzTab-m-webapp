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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.lifstools.mztab.validator.webapp.service.StorageService;
import org.lifstools.mztab.validator.webapp.service.StorageService.SLOT;
import org.springframework.stereotype.Component;

/**
 * Reads text from the stored upload of a session. The text is only quoted back to the user; it is never
 * parsed as mzTab-M here.
 */
@Component
public class UploadLines {

    static final String UPLOAD = "upload.mztab";

    private static final Pattern SOFTWARE = Pattern.compile("^MTD\\tsoftware\\[\\d+\\]\\t(.*)$");

    private final StorageService storage;

    public UploadLines(StorageService storage) {
        this.storage = storage;
    }

    /**
     * Reads the upload once.
     *
     * @return the text of the requested 1-based lines that exist; empty if there is no upload
     */
    public Map<Long, String> lines(UUID session, Set<Long> lineNumbers) {
        if (lineNumbers.isEmpty()) {
            return new HashMap<>();
        }
        return read(session, in -> lines(in, lineNumbers), HashMap::new);
    }

    /** The names in the {@code MTD software[n]} parameters, in file order. */
    public List<String> softwareNames(UUID session) {
        return read(session, UploadLines::softwareNames, ArrayList::new);
    }

    /**
     * As {@link #lines(UUID, Set)}, from any text, e.g. the assembled text of a
     * working copy; the caller closes {@code in}.
     */
    public static Map<Long, String> lines(InputStream in, Set<Long> lineNumbers) throws IOException {
        Map<Long, String> found = new HashMap<>();
        if (lineNumbers.isEmpty()) {
            return found;
        }
        long last = lineNumbers.stream().mapToLong(Long::longValue).max().orElse(0);
        scan(in, (number, text) -> {
            if (lineNumbers.contains(number)) {
                found.put(number, text);
            }
            return number < last;
        });
        return found;
    }

    /** As {@link #softwareNames(UUID)}, from any text; the caller closes {@code in}. */
    public static List<String> softwareNames(InputStream in) throws IOException {
        List<String> names = new ArrayList<>();
        scan(in, (number, text) -> {
            // the metadata section ends at the first line that is not MTD, COM or blank
            if (!text.isBlank() && !text.startsWith("MTD") && !text.startsWith("COM")) {
                return false;
            }
            Matcher m = SOFTWARE.matcher(text);
            if (m.matches()) {
                nameOf(m.group(1).strip()).ifPresent(names::add);
            }
            return true;
        });
        return names;
    }

    @FunctionalInterface
    private interface Reading<T> {

        T read(InputStream in) throws IOException;
    }

    private <T> T read(UUID session, Reading<T> reading, java.util.function.Supplier<T> none) {
        Optional<InputStream> in = storage.read(session, SLOT.MZTABFILE, UPLOAD);
        if (in.isEmpty()) {
            return none.get();
        }
        try (InputStream stream = in.get()) {
            return reading.read(stream);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the upload of validation " + session, e);
        }
    }

    /** The third slot of a parameter {@code [label, accession, name, value]}. */
    static Optional<String> nameOf(String parameter) {
        if (!parameter.startsWith("[") || !parameter.endsWith("]")) {
            return Optional.empty();
        }
        List<String> slots = new ArrayList<>();
        StringBuilder slot = new StringBuilder();
        boolean quoted = false;
        for (char c : parameter.substring(1, parameter.length() - 1).toCharArray()) {
            if (c == '"') {
                quoted = !quoted;
            } else if (c == ',' && !quoted) {
                slots.add(slot.toString().strip());
                slot.setLength(0);
            } else {
                slot.append(c);
            }
        }
        slots.add(slot.toString().strip());
        return slots.size() >= 3 && !slots.get(2).isEmpty() ? Optional.of(slots.get(2)) : Optional.empty();
    }

    private interface LineVisitor {

        /** @return whether to go on reading */
        boolean visit(long number, String text);
    }

    /** At most this many characters of a line are kept: one more than the quote limit, so a cut can be seen. */
    static final int KEPT_CHARS = GuidanceResolver.MAX_LINE_CHARS + 1;

    private static void scan(InputStream in, LineVisitor visitor) throws IOException {
        // InputStreamReader replaces malformed input, so a file in another encoding does not fail
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        long number = 0;
        StringBuilder line = new StringBuilder();
        boolean any = false;
        boolean afterCr = false;
        int c = reader.read();
        // a byte order mark is not part of the first line's text
        if (c == '\uFEFF') {
            c = reader.read();
        }
        for (; c != -1; c = reader.read()) {
            if (c == '\n' && afterCr) {
                afterCr = false;
                continue;
            }
            afterCr = c == '\r';
            if (c == '\n' || c == '\r') {
                number++;
                String text = line.toString();
                line.setLength(0);
                any = false;
                if (!visitor.visit(number, text)) {
                    return;
                }
            } else {
                any = true;
                // a line can be as long as the upload: the rest of a long line is skipped, not kept
                if (line.length() < KEPT_CHARS) {
                    line.append((char) c);
                }
            }
        }
        if (any) {
            visitor.visit(number + 1, line.toString());
        }
    }
}
