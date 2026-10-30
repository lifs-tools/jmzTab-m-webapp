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
package org.lifstools.mztab.validator.webapp.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.lifstools.mztab.validator.engine.api.ValidationEvent;
import org.lifstools.mztab.validator.webapp.sections.MetadataEntry;
import org.lifstools.mztab.validator.webapp.sections.Section;
import org.lifstools.mztab.validator.webapp.sections.SectionReader;
import org.lifstools.mztab.validator.webapp.service.StorageService;
import org.lifstools.mztab.validator.webapp.service.StorageService.SLOT;
import org.lifstools.mztab.validator.webapp.session.SectionsSummary;
import org.lifstools.mztab.validator.webapp.session.TableSize;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Reads an engine's NDJSON validation stream: the outcome, and the section
 * data of a parsed file stored as the files {@link SectionReader} reads, i.e.
 * {@value SectionReader#METADATA_FILE} (one {@link MetadataEntry} per line)
 * and one {@link Section#fileName()} per present table (one JSON array of
 * cells per line, the header first). Each file is spooled through a temporary
 * file and stored once the stream is complete, so readers never see a
 * partial result; a session's sections are never held in memory.
 */
@Slf4j
@Component
public class EngineResultReader {

    /**
     * What a validation stream held.
     *
     * @param sections the stored sections; {@code null} if the file was not
     * parsed, the engine sent none, or they could not be stored
     */
    public record EngineResult(ValidationEvent.Outcome outcome, @Nullable SectionsSummary sections) {

    }

    private final StorageService storage;
    private final ObjectReader events;
    private final ObjectWriter json;

    public EngineResultReader(StorageService storage) {
        this.storage = storage;
        ObjectMapper mapper = new ObjectMapper();
        this.events = mapper.readerFor(ValidationEvent.class)
            .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.json = mapper.writer();
    }

    /**
     * Reads the stream to its end and stores the sections of validation
     * {@code id}.
     *
     * @throws TruncatedEngineStreamException if the stream ends before its
     * {@code end} event; the validation's sections are deleted
     * @throws UncheckedIOException if the stream cannot be read or is not a
     * validation stream; the validation's sections are deleted
     */
    public EngineResult read(UUID id, InputStream ndjson) {
        Spools spools = new Spools();
        try {
            return readStream(id, ndjson, spools);
        } catch (IOException e) {
            removeSections(id);
            throw new UncheckedIOException("The engine's response for validation " + id + " could not be read", e);
        } catch (RuntimeException e) {
            removeSections(id);
            throw e;
        } finally {
            spools.close();
        }
    }

    /**
     * Reads the stream to its end and keeps only the outcome, for a validation
     * without a session: the section events are read and dropped, one line at
     * a time.
     *
     * @throws TruncatedEngineStreamException if the stream ends before its
     * {@code end} event
     * @throws UncheckedIOException if the stream cannot be read or is not a
     * validation stream
     */
    public ValidationEvent.Outcome readOutcome(InputStream ndjson) {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(ndjson, StandardCharsets.UTF_8));
            ValidationEvent.Outcome outcome = null;
            String text;
            while ((text = in.readLine()) != null) {
                if (text.isBlank()) {
                    continue;
                }
                ValidationEvent event = parse(text);
                if (outcome == null) {
                    if (!(event instanceof ValidationEvent.Outcome o)) {
                        throw malformed("the first event is not the outcome");
                    }
                    outcome = o;
                } else if (event instanceof ValidationEvent.Outcome) {
                    throw malformed("a second outcome");
                } else if (event instanceof ValidationEvent.End) {
                    return outcome;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("The engine's response could not be read", e);
        }
        log.warn("A synchronous validation: the engine's response ended before its end event");
        throw new TruncatedEngineStreamException("The engine's response ended before its end event.");
    }

    private EngineResult readStream(UUID id, InputStream ndjson, Spools spools) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(ndjson, StandardCharsets.UTF_8));
        ValidationEvent.Outcome outcome = null;
        Section table = null;
        String text;
        while ((text = in.readLine()) != null) {
            if (text.isBlank()) {
                continue;
            }
            ValidationEvent event = parse(text);
            if (outcome == null && !(event instanceof ValidationEvent.Outcome)) {
                throw malformed("the first event is not the outcome");
            }
            switch (event) {
                case ValidationEvent.Outcome o -> {
                    if (outcome != null) {
                        throw malformed("a second outcome");
                    }
                    outcome = o;
                }
                case ValidationEvent.MetadataLine m -> {
                    if (table != null) {
                        throw malformed("metadata after a table");
                    }
                    spools.metadata().write(new MetadataEntry(m.line(), m.key(), m.value()));
                }
                case ValidationEvent.TableHeader h -> {
                    table = section(h.section());
                    if (spools.tables.containsKey(table)) {
                        throw malformed("the " + table + " table twice");
                    }
                    if (h.header() == null || h.header().isEmpty()) {
                        throw malformed("a " + table + " table without columns");
                    }
                    spools.table(table, h.header().size()).write(h.header());
                }
                case ValidationEvent.TableRowCells r -> {
                    if (table == null || section(r.section()) != table) {
                        throw malformed("a " + r.section() + " row outside its table");
                    }
                    spools.tables.get(table).row(r.cells());
                }
                case ValidationEvent.End e -> {
                    SectionsSummary sections = outcome.parsed() && e.sections() != null ? store(id, spools) : null;
                    return new EngineResult(outcome, sections);
                }
            }
        }
        log.warn("Validation {}: the engine's response ended before its end event", id);
        throw new TruncatedEngineStreamException("The engine's response for validation " + id
            + " ended before its end event.");
    }

    private ValidationEvent parse(String text) throws IOException {
        try {
            return events.readValue(text);
        } catch (JsonProcessingException e) {
            throw malformed("an unreadable event: " + e.getOriginalMessage());
        }
    }

    private static Section section(String name) throws IOException {
        if (name != null) {
            for (Section s : Section.values()) {
                if (s.name().equals(name)) {
                    return s;
                }
            }
        }
        throw malformed("an unknown section " + name);
    }

    private static IOException malformed(String detail) {
        return new IOException("The engine's response is not a validation stream: " + detail);
    }

    /**
     * The section data is a view of the file; failing to store it must not
     * discard the validation result.
     *
     * @return {@code null} if the sections could not be stored
     */
    @Nullable
    private SectionsSummary store(UUID id, Spools spools) {
        try {
            IOException failure = spools.failure();
            if (failure != null) {
                throw failure;
            }
            Map<Section, TableSize> sizes = new EnumMap<>(Section.class);
            Spool metadata = spools.metadata();
            metadata.finish();
            for (Map.Entry<Section, Spool> table : spools.tables.entrySet()) {
                Spool spool = table.getValue();
                spool.finish();
                storeFile(id, table.getKey().fileName(), spool);
                sizes.put(table.getKey(), new TableSize(spool.lines - 1, spool.columns));
            }
            // the metadata last: a session with metadata has all of its tables
            storeFile(id, SectionReader.METADATA_FILE, metadata);
            return new SectionsSummary(metadata.lines, sizes.get(Section.SML), sizes.get(Section.SMF),
                sizes.get(Section.SME));
        } catch (IOException | RuntimeException e) {
            log.error("Validation {}: could not store the sections; the result is kept without them", id, e);
            removeSections(id);
            return null;
        }
    }

    private void storeFile(UUID id, String name, Spool spool) throws IOException {
        try (InputStream in = Files.newInputStream(spool.file)) {
            storage.write(id, SLOT.SECTIONS, name, in);
        }
    }

    private void removeSections(UUID id) {
        try {
            storage.delete(id, SLOT.SECTIONS);
        } catch (RuntimeException e) {
            log.warn("Validation {}: could not delete partial sections", id, e);
        }
    }

    /** The temporary files of one stream: the metadata and the tables in stream order. */
    private final class Spools {

        private Spool metadata;
        private final Map<Section, Spool> tables = new EnumMap<>(Section.class);

        Spool metadata() throws IOException {
            if (metadata == null) {
                metadata = new Spool(0);
            }
            return metadata;
        }

        Spool table(Section section, int columns) throws IOException {
            Spool spool = new Spool(columns);
            tables.put(section, spool);
            return spool;
        }

        /** The first local write failure of any file; the stream is still read to its end. */
        @Nullable
        IOException failure() {
            if (metadata != null && metadata.failed != null) {
                return metadata.failed;
            }
            return tables.values().stream().map(t -> t.failed).filter(f -> f != null).findFirst().orElse(null);
        }

        void close() {
            if (metadata != null) {
                metadata.delete();
            }
            tables.values().forEach(Spool::delete);
        }
    }

    /** One temporary file, one JSON value per line. */
    private final class Spool {

        final Path file;
        final int columns;
        private final OutputStream out;
        int lines;
        IOException failed;

        Spool(int columns) throws IOException {
            this.columns = columns;
            this.file = Files.createTempFile("mztab-sections-", ".jsonl");
            this.out = new BufferedOutputStream(Files.newOutputStream(file));
        }

        void write(Object value) {
            lines++;
            if (failed != null) {
                return;
            }
            try {
                out.write(json.writeValueAsBytes(value));
                out.write('\n');
            } catch (IOException e) {
                failed = e;
            }
        }

        void row(@Nullable List<String> cells) throws IOException {
            if (cells == null) {
                throw malformed("a row without cells");
            }
            write(cells);
        }

        void finish() throws IOException {
            out.close();
        }

        void delete() {
            try {
                out.close();
            } catch (IOException e) {
                // deleted next
            }
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                log.warn("Could not delete temporary file {}", file, e);
            }
        }
    }
}
