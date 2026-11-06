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
package org.lifstools.mztab.validator.webapp.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.InputStream;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lifstools.mztab.validator.engine.api.ValidationLevel;
import org.lifstools.mztab.validator.webapp.service.storage.FileSystemStorageService;
import org.lifstools.mztab.validator.webapp.service.storage.StorageProperties;
import org.lifstools.mztab.validator.engine.api.ValidationLocation;
import org.lifstools.mztab.validator.engine.api.ValidationMessage;
import org.lifstools.mztab.validator.webapp.service.StorageService.SLOT;
import org.lifstools.mztab2.server.config.JacksonConfiguration;

class ValidationRecordStoreTest {

    @TempDir
    Path root;

    private ValidationRecordStore store;
    private FileSystemStorageService storage;

    @BeforeEach
    void setUp() {
        StorageProperties properties = new StorageProperties();
        properties.setLocation(root.toString());
        storage = new FileSystemStorageService(properties);
        storage.init();
        store = new ValidationRecordStore(storage, new JacksonConfiguration().objectMapper());
    }

    @Test
    void updatesOfOneSessionAreSerialised() throws Exception {
        Instant now = Instant.now();
        UUID id = UUID.randomUUID();
        store.write(ValidationRecord.queued(id, "a.mzTab", ValidationLevel.INFO, 100, ProfileRef.none(), now,
            now.plus(Duration.ofDays(7))));
        UnaryOperator<ValidationRecord> start = r -> r.status() == ValidationStatus.QUEUED ? r.running(now) : null;
        CountDownLatch insideFirst = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Optional<ValidationRecord>> first = new AtomicReference<>();
        AtomicReference<Optional<ValidationRecord>> second = new AtomicReference<>();

        Thread a = new Thread(() -> first.set(store.update(id, r -> {
            insideFirst.countDown();
            awaitQuietly(release);
            return start.apply(r);
        })));
        Thread b = new Thread(() -> second.set(store.update(id, start)));
        try {
            a.start();
            assertTrue(insideFirst.await(10, TimeUnit.SECONDS));
            b.start();
            // b must wait for a's lock; without one it reads QUEUED and finishes at once
            Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
            while (b.getState() != Thread.State.BLOCKED && b.isAlive()) {
                assertTrue(Instant.now().isBefore(deadline), "second update neither blocked nor finished");
                Thread.sleep(5);
            }
        } finally {
            release.countDown();
            a.join();
            b.join();
        }

        assertTrue(first.get().isPresent(), "the first update starts the run");
        assertTrue(second.get().isEmpty(), "the second update must see RUNNING and change nothing");
        assertEquals(ValidationStatus.RUNNING, store.read(id).orElseThrow().status());
    }

    @Test
    void keepsSuggestionRuleIdAndLocation() throws Exception {
        Instant now = Instant.now();
        UUID id = UUID.randomUUID();
        ValidationLocation location = new ValidationLocation();
        location.setSection(ValidationLocation.Section.SML);
        location.setRowIndex(3);
        location.setColumn("reliability");
        location.setMetadataKey("sample[3]-species[1]");
        location.setMetadataGroup("sample");
        location.setElementPath("/a/b");
        ValidationMessage message = new ValidationMessage();
        message.setCode("2009");
        message.setMessage("m");
        message.setLineNumber(5L);
        message.setMessageType(ValidationMessage.MessageType.ERROR);
        message.setCategory(ValidationMessage.Category.LOGICAL);
        message.setSuggestion("Add the column.");
        message.setRuleId("rule-1");
        message.setLocation(location);
        store.write(ValidationRecord.queued(id, "a.mzTab", ValidationLevel.INFO, 100, ProfileRef.none(), now,
            now.plus(Duration.ofDays(7))).running(now).finished(ValidationOutcome.FAILED, "2.0.0-M", List.of(message),
            null, "mzTab-M 2.0 · jmzTab-m 1.0.8 (build b)", true, now));

        ValidationRecord loaded = store.read(id).orElseThrow();

        assertEquals(message, loaded.messages().get(0));
        assertEquals("mzTab-M 2.0 · jmzTab-m 1.0.8 (build b)", loaded.engine());
        assertTrue(loaded.defaultVersionApplied());
    }

    /**
     * A record written before validations ran through the engines (with
     * jmzTab-m's messages, without {@code engine} and
     * {@code defaultVersionApplied}) still reads, and its messages are served
     * with the same JSON.
     */
    @Test
    void recordWrittenBeforeTheEnginesStillReads() throws Exception {
        byte[] before;
        try (InputStream in = getClass().getResourceAsStream("/engine-results/record-before-engines.json")) {
            before = in.readAllBytes();
        }
        UUID id = UUID.fromString("6f1c9a4e-2b7d-4c1e-9a3f-0d5e8b7c6a21");
        storage.write(id, SLOT.RECORD, ValidationRecordStore.RECORD, new ByteArrayInputStream(before));

        ValidationRecord record = store.read(id).orElseThrow();

        assertEquals(ValidationStatus.FINISHED, record.status());
        assertEquals(ValidationOutcome.FAILED, record.outcome());
        assertEquals(ProfileRef.catalog("mztab-m-2.0-default", "1.0.0"), record.validationProfile());
        assertNull(record.engine());
        assertFalse(record.defaultVersionApplied());
        assertEquals(2, record.messages().size());
        ValidationMessage first = record.messages().get(0);
        assertEquals(ValidationMessage.MessageType.ERROR, first.getMessageType());
        assertEquals(ValidationMessage.Category.CROSS_CHECK, first.getCategory());
        assertEquals(ValidationLocation.Section.SML, first.getLocation().getSection());
        assertEquals(3, first.getLocation().getRowIndex());
        assertEquals(1, record.counts().get(ValidationLevel.ERROR));
        assertEquals(1, record.counts().get(ValidationLevel.WARN));
        assertEquals(new SectionsSummary(61, new TableSize(1, 19), new TableSize(4, 12), null), record.sections());
        ObjectMapper mapper = new JacksonConfiguration().objectMapper();
        assertEquals(mapper.writeValueAsString(mapper.readTree(before).get("messages")),
            mapper.writeValueAsString(record.messages()), "the messages keep their JSON");
        JsonNode written = mapper.valueToTree(record);
        assertTrue(written.has("engine") && written.get("engine").isNull(), written.toString());
        assertFalse(written.get("defaultVersionApplied").asBoolean());
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
