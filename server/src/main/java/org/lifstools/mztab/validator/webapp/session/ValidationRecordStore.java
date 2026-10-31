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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.lifstools.mztab.validator.webapp.service.StorageService;
import org.lifstools.mztab.validator.webapp.service.StorageService.SLOT;
import org.lifstools.mztab.validator.webapp.service.storage.StorageException;
import org.springframework.stereotype.Component;

/**
 * Reads and writes {@code RECORD/record.json} through {@link StorageService}.
 * Changes to one session's record are serialised by a per-id lock, so a
 * read-check-write such as {@code QUEUED -> RUNNING} happens at most once.
 */
@Component
class ValidationRecordStore {

    static final String RECORD = "record.json";
    private static final int STRIPES = 64;

    private final StorageService storage;
    private final ObjectMapper mapper;
    private final ObjectReader reader;
    private final Object[] locks = new Object[STRIPES];

    ValidationRecordStore(StorageService storage, ObjectMapper mapper) {
        this.storage = storage;
        this.mapper = mapper;
        // records outlive deployments; tolerate fields a later version may add
        this.reader = mapper.readerFor(ValidationRecord.class)
            .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        for (int i = 0; i < STRIPES; i++) {
            locks[i] = new Object();
        }
    }

    Optional<ValidationRecord> read(UUID id) {
        Optional<InputStream> in = storage.read(id, SLOT.RECORD, RECORD);
        if (in.isEmpty()) {
            return Optional.empty();
        }
        try (InputStream is = in.get()) {
            return Optional.of(reader.readValue(is));
        } catch (IOException e) {
            throw new StorageException("Could not read the record of validation " + id, e);
        }
    }

    void write(ValidationRecord record) {
        synchronized (lock(record.id())) {
            persist(record);
        }
    }

    /**
     * Applies {@code change} to the current record under the session's lock.
     *
     * @param change returns the new record, or {@code null} to leave the record unchanged
     * @return the written record; empty if there is no record or nothing changed
     */
    Optional<ValidationRecord> update(UUID id, UnaryOperator<ValidationRecord> change) {
        synchronized (lock(id)) {
            Optional<ValidationRecord> updated = read(id).map(change);
            updated.ifPresent(this::persist);
            return updated;
        }
    }

    /** Deletes the whole session, if it has a record. */
    boolean delete(UUID id) {
        synchronized (lock(id)) {
            if (read(id).isEmpty()) {
                return false;
            }
            storage.deleteAll(id);
            return true;
        }
    }

    private void persist(ValidationRecord record) {
        try {
            storage.write(record.id(), SLOT.RECORD, RECORD,
                new ByteArrayInputStream(mapper.writeValueAsBytes(record.withGuidance(null))));
        } catch (JsonProcessingException e) {
            throw new StorageException("Could not serialise the record of validation " + record.id(), e);
        }
    }

    private Object lock(UUID id) {
        return locks[Math.floorMod(id.hashCode(), STRIPES)];
    }
}
