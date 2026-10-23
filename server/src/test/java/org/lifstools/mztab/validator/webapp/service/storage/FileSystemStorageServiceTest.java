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
package org.lifstools.mztab.validator.webapp.service.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lifstools.mztab.validator.webapp.service.StorageService.SLOT;

class FileSystemStorageServiceTest {

    @TempDir
    Path root;

    private FileSystemStorageService storage;

    @BeforeEach
    void setUp() {
        StorageProperties properties = new StorageProperties();
        properties.setLocation(root.toString());
        storage = new FileSystemStorageService(properties);
        storage.setMinCleanupAge(7);
        storage.init();
    }

    @Test
    void writeIsAtomicAndReadable() throws IOException {
        UUID id = UUID.randomUUID();

        storage.write(id, SLOT.RECORD, "record.json", stream("hello"));
        assertEquals("hello", read(id, SLOT.RECORD, "record.json"));

        storage.write(id, SLOT.RECORD, "record.json", stream("world!"));
        assertEquals("world!", read(id, SLOT.RECORD, "record.json"));

        try (Stream<Path> files = Files.list(root.resolve(id.toString()).resolve(SLOT.RECORD.name()))) {
            assertEquals(List.of("record.json"), files.map(p -> p.getFileName().toString()).toList(),
                "no temporary files may be left behind");
        }
        assertEquals(List.of(id), storage.sessions().toList());
        assertEquals(6, storage.totalBytes());
        assertTrue(storage.read(id, SLOT.RECORD, "other.json").isEmpty());
        assertTrue(storage.read(UUID.randomUUID(), SLOT.RECORD, "record.json").isEmpty());
    }

    @Test
    void rejectsUnsafeNames() throws IOException {
        UUID id = UUID.randomUUID();
        for (String name : List.of("../x", "..", ".", "", "a/b", "a\\b", "x".repeat(65))) {
            assertThrows(IllegalArgumentException.class,
                () -> storage.write(id, SLOT.RECORD, name, stream("x")), "name '" + name + "'");
            assertThrows(IllegalArgumentException.class,
                () -> storage.read(id, SLOT.RECORD, name), "name '" + name + "'");
        }
        assertFalse(Files.exists(root.resolve(id.toString())));
        assertFalse(Files.exists(root.resolve("x")));
    }

    @Test
    void cleanupRemovesRecordAndSections() throws IOException {
        UUID old = UUID.randomUUID();
        UUID fresh = UUID.randomUUID();
        for (UUID id : List.of(old, fresh)) {
            storage.write(id, SLOT.MZTABFILE, "upload.mztab", stream("MTD"));
            storage.write(id, SLOT.RECORD, "record.json", stream("{}"));
            storage.write(id, SLOT.SECTIONS, "sml.jsonl", stream("[]"));
        }
        Files.setLastModifiedTime(root.resolve(old.toString()),
            FileTime.from(Instant.now().minus(Duration.ofDays(8))));

        storage.deleteOldFiles();

        assertEquals(List.of(fresh), storage.sessions().toList());
        assertTrue(storage.read(old, SLOT.RECORD, "record.json").isEmpty());
        assertTrue(storage.read(old, SLOT.SECTIONS, "sml.jsonl").isEmpty());
        assertFalse(Files.exists(root.resolve(old.toString())));
        assertEquals("{}", read(fresh, SLOT.RECORD, "record.json"));
    }

    private String read(UUID id, SLOT slot, String name) throws IOException {
        Optional<InputStream> in = storage.read(id, slot, name);
        assertTrue(in.isPresent(), slot + "/" + name + " must exist");
        try (InputStream is = in.get()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static InputStream stream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }
}
