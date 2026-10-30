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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lifstools.mztab.validator.engine.api.ValidationMessage;
import org.lifstools.mztab.validator.webapp.sections.Section;
import org.lifstools.mztab.validator.webapp.sections.SectionReader;
import org.lifstools.mztab.validator.webapp.sections.TableQuery;
import org.lifstools.mztab.validator.webapp.service.StorageService.SLOT;
import org.lifstools.mztab.validator.webapp.service.storage.FileSystemStorageService;
import org.lifstools.mztab.validator.webapp.service.storage.StorageProperties;
import org.lifstools.mztab.validator.webapp.session.SectionsSummary;
import org.lifstools.mztab.validator.webapp.session.TableSize;

/**
 * Reading an engine's NDJSON validation stream into the section files that
 * {@link SectionReader} serves. The fixture {@code lipidomics-example.ndjson}
 * is the 2.0 engine's response for jmzTab-m's {@code lipidomics-example.mzTab};
 * {@code lipidomics-example-sections/} holds the files the webapp wrote for the
 * same file before validations ran through the engines.
 */
class EngineResultReaderTest {

    private static final String NDJSON = "/engine-results/lipidomics-example.ndjson";
    private static final String BEFORE = "/engine-results/lipidomics-example-sections/";
    private static final List<String> FILES = List.of(SectionReader.METADATA_FILE, Section.SML.fileName(),
        Section.SMF.fileName(), Section.SME.fileName());

    @TempDir
    Path root;

    private FileSystemStorageService storage;
    private EngineResultReader reader;
    private SectionReader sections;

    @BeforeEach
    void setUp() {
        StorageProperties properties = new StorageProperties();
        properties.setLocation(root.toString());
        storage = new FileSystemStorageService(properties);
        storage.init();
        reader = new EngineResultReader(storage);
        sections = new SectionReader(storage, 1_000_000);
    }

    @Test
    void writesSectionsInSectionReaderFormat() throws Exception {
        UUID id = UUID.randomUUID();
        UUID before = UUID.randomUUID();
        for (String name : FILES) {
            storage.write(before, SLOT.SECTIONS, name, new ByteArrayInputStream(resource(BEFORE + name)));
        }

        EngineResultReader.EngineResult result = reader.read(id, new ByteArrayInputStream(resource(NDJSON)));

        assertTrue(result.outcome().parsed());
        assertEquals("2.0.0-M", result.outcome().mzTabVersion());
        assertEquals(1, result.outcome().messages().size(), "messages: " + result.outcome().messages());
        assertEquals(ValidationMessage.MessageType.INFO, result.outcome().messages().get(0).getMessageType());
        assertEquals(new SectionsSummary(61, new TableSize(1, 19), new TableSize(4, 12), new TableSize(4, 20)),
            result.sections());
        for (String name : FILES) {
            assertArrayEquals(resource(BEFORE + name), stored(id, name), name + " differs from the pre-engine file");
        }
        assertEquals(sections.metadataGroups(before), sections.metadataGroups(id));
        assertFalse(sections.metadataGroups(id).isEmpty());
        assertEquals(sections.metadata(before, "assay", 0, 50).orElseThrow().entries(),
            sections.metadata(id, "assay", 0, 50).orElseThrow().entries());
        for (Section section : Section.values()) {
            TableQuery all = new TableQuery(0, 200, null, false, List.of(), List.of(), List.of());
            assertEquals(sections.table(before, section, all).orElseThrow().rows(),
                sections.table(id, section, all).orElseThrow().rows(), section.name());
            assertEquals(sections.table(before, section, all).orElseThrow().allColumns(),
                sections.table(id, section, all).orElseThrow().allColumns(), section.name());
        }
    }

    @Test
    void truncatedEngineStreamFailsValidationAndRemovesSections() throws Exception {
        String complete = new String(resource(NDJSON), StandardCharsets.UTF_8);
        List<String> lines = complete.lines().toList();
        // without the end event, and cut in the middle of the last table
        for (int keep : new int[]{lines.size() - 1, lines.size() - 3, 1}) {
            UUID id = UUID.randomUUID();
            String truncated = String.join("\n", lines.subList(0, keep)) + "\n";
            // whatever the slot holds goes, e.g. files of an earlier attempt
            storage.write(id, SLOT.SECTIONS, SectionReader.METADATA_FILE,
                new ByteArrayInputStream("{}\n".getBytes(StandardCharsets.UTF_8)));

            assertThrows(TruncatedEngineStreamException.class,
                () -> reader.read(id, new ByteArrayInputStream(truncated.getBytes(StandardCharsets.UTF_8))));

            assertNoSections(id);
        }
    }

    @Test
    void truncatedStreamIsAnUncheckedIoException() {
        // the engine client reports an UncheckedIOException of its reader as the engine being unavailable
        assertTrue(UncheckedIOException.class.isAssignableFrom(TruncatedEngineStreamException.class));
    }

    @Test
    void streamFailingWhileReadIsRethrownAndRemovesSections() throws Exception {
        UUID id = UUID.randomUUID();
        byte[] complete = resource(NDJSON);
        InputStream dying = new InputStream() {
            private int read;

            @Override
            public int read() throws IOException {
                if (read >= complete.length - 200) {
                    throw new IOException("connection reset");
                }
                return complete[read++];
            }
        };

        assertThrows(UncheckedIOException.class, () -> reader.read(id, dying));

        assertNoSections(id);
    }

    @Test
    void malformedLineFailsAndRemovesSections() throws Exception {
        UUID id = UUID.randomUUID();
        List<String> lines = new String(resource(NDJSON), StandardCharsets.UTF_8).lines().toList();
        String malformed = String.join("\n", lines.subList(0, 5)) + "\n{\"type\":\"row\",\"sect\n"
            + String.join("\n", lines.subList(5, lines.size())) + "\n";

        assertThrows(UncheckedIOException.class,
            () -> reader.read(id, new ByteArrayInputStream(malformed.getBytes(StandardCharsets.UTF_8))));

        assertNoSections(id);
    }

    @Test
    void rowOfAnotherSectionThanItsHeaderIsMalformed() {
        UUID id = UUID.randomUUID();
        String ndjson = """
            {"type":"outcome","parsed":true,"mzTabVersion":"2.0.0-M","messages":[]}
            {"type":"metadata","line":1,"key":"mzTab-version","value":"2.0.0-M"}
            {"type":"table","section":"SML","header":["SML_ID"]}
            {"type":"row","section":"SMF","cells":["1"]}
            {"type":"end","sections":{"metadataLines":1,"sml":{"rows":1,"columns":1}}}
            """;

        assertThrows(UncheckedIOException.class,
            () -> reader.read(id, new ByteArrayInputStream(ndjson.getBytes(StandardCharsets.UTF_8))));

        assertNoSections(id);
    }

    @Test
    void unparsedOutcomeWritesNoSections() {
        UUID id = UUID.randomUUID();
        String ndjson = """
            {"type":"outcome","parsed":false,"mzTabVersion":null,"messages":[{"code":"","category":"format","message_type":"error","message":"The JSON file could not be read: x","line_number":-1}]}
            {"type":"end","sections":null}
            """;

        EngineResultReader.EngineResult result = reader.read(id,
            new ByteArrayInputStream(ndjson.getBytes(StandardCharsets.UTF_8)));

        assertFalse(result.outcome().parsed());
        assertNull(result.outcome().mzTabVersion());
        assertEquals("The JSON file could not be read: x", result.outcome().messages().get(0).getMessage());
        assertNull(result.sections());
        assertNoSections(id);
    }

    @Test
    void absentTablesWriteNoFile() throws Exception {
        UUID id = UUID.randomUUID();
        String ndjson = """
            {"type":"outcome","parsed":true,"mzTabVersion":"2.0.0-M","messages":[]}
            {"type":"metadata","line":1,"key":"mzTab-version","value":"2.0.0-M"}
            {"type":"table","section":"SML","header":["SML_ID","chemical_name"]}
            {"type":"row","section":"SML","cells":["1","a"]}
            {"type":"row","section":"SML","cells":["2","b"]}
            {"type":"end","sections":{"metadataLines":1,"sml":{"rows":2,"columns":2}}}
            """;

        EngineResultReader.EngineResult result = reader.read(id,
            new ByteArrayInputStream(ndjson.getBytes(StandardCharsets.UTF_8)));

        assertEquals(new SectionsSummary(1, new TableSize(2, 2), null, null), result.sections());
        assertTrue(storage.read(id, SLOT.SECTIONS, Section.SML.fileName()).isPresent());
        assertTrue(storage.read(id, SLOT.SECTIONS, Section.SMF.fileName()).isEmpty());
        assertTrue(storage.read(id, SLOT.SECTIONS, Section.SME.fileName()).isEmpty());
        assertEquals("[\"SML_ID\",\"chemical_name\"]\n[\"1\",\"a\"]\n[\"2\",\"b\"]\n",
            new String(stored(id, Section.SML.fileName()), StandardCharsets.UTF_8));
    }

    @Test
    void storageFailureKeepsTheOutcomeWithoutSections() throws Exception {
        FileSystemStorageService failing = spy(storage);
        UUID id = UUID.randomUUID();
        doThrow(new UncheckedIOException(new IOException("disk full"))).when(failing)
            .write(eq(id), eq(SLOT.SECTIONS), eq(Section.SMF.fileName()), any());

        EngineResultReader.EngineResult result = new EngineResultReader(failing).read(id,
            new ByteArrayInputStream(resource(NDJSON)));

        assertNotNull(result.outcome());
        assertTrue(result.outcome().parsed());
        assertNull(result.sections(), "the sections could not be stored");
        assertNoSections(id);
    }

    private void assertNoSections(UUID id) {
        for (String name : FILES) {
            assertTrue(storage.read(id, SLOT.SECTIONS, name).isEmpty(), name + " must not be left behind");
        }
    }

    private byte[] stored(UUID id, String name) throws IOException {
        try (InputStream in = storage.read(id, SLOT.SECTIONS, name).orElseThrow(
            () -> new AssertionError(name + " was not written"))) {
            return in.readAllBytes();
        }
    }

    private byte[] resource(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(name)) {
            assertNotNull(in, name);
            return in.readAllBytes();
        }
    }
}
