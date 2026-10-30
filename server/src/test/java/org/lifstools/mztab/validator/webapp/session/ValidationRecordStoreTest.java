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
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.lifstools.mztab2.model.ValidationLocation;
import org.lifstools.mztab2.model.ValidationMessage;
import org.lifstools.mztab2.server.config.JacksonConfiguration;

class ValidationRecordStoreTest {

    @TempDir
    Path root;

    private ValidationRecordStore store;

    @BeforeEach
    void setUp() {
        StorageProperties properties = new StorageProperties();
        properties.setLocation(root.toString());
        FileSystemStorageService storage = new FileSystemStorageService(properties);
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
        ValidationMessage message = new ValidationMessage().code("2009").message("m").lineNumber(5L)
            .messageType(ValidationMessage.MessageTypeEnum.ERROR).category(ValidationMessage.CategoryEnum.LOGICAL)
            .suggestion("Add the column.").ruleId("rule-1")
            .location(new ValidationLocation().section(ValidationLocation.SectionEnum.SML).rowIndex(3).column("reliability")
                .metadataKey("sample[3]-species[1]").metadataGroup("sample").elementPath("/a/b"));
        store.write(ValidationRecord.queued(id, "a.mzTab", ValidationLevel.INFO, 100, ProfileRef.none(), now,
            now.plus(Duration.ofDays(7))).running(now).finished(ValidationOutcome.FAILED, "2.0.0-M", List.of(message), null, now));

        ValidationMessage loaded = store.read(id).orElseThrow().messages().get(0);

        assertEquals(message, loaded);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
