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

import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.lifstools.mztab.validator.webapp.service.StorageService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * On startup, fails every validation that was queued or running when the
 * server stopped. With a single replica per instance, nothing else can be
 * running them.
 */
@Slf4j
@Component
public class SessionRecovery implements ApplicationRunner {

    static final String INTERRUPTED
        = "The validation was interrupted by a server restart. Please validate the file again.";

    private final StorageService storage;
    private final ValidationRecordStore records;

    public SessionRecovery(StorageService storage, ValidationRecordStore records) {
        this.storage = storage;
        this.records = records;
    }

    @Override
    public void run(ApplicationArguments args) {
        Instant now = Instant.now();
        storage.sessions().forEach(id -> {
            try {
                records.update(id, r -> r.status() == ValidationStatus.QUEUED
                    || r.status() == ValidationStatus.RUNNING ? r.failed(INTERRUPTED, now) : null)
                    .ifPresent(r -> log.info("Validation {} was interrupted by a restart", id));
            } catch (RuntimeException e) {
                log.warn("Could not recover validation {}", id, e);
            }
        });
    }
}
