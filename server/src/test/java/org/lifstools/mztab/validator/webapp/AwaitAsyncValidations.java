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
package org.lifstools.mztab.validator.webapp;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * Waits after each test until the validations it started have finished.
 * {@code POST /rest/v2/validations} answers 202 and validates in the
 * background; a validation still running when the test class ends writes into
 * its {@code @TempDir} while JUnit deletes it, which fails the class with
 * "Failed to delete temp directory".
 */
public class AwaitAsyncValidations implements AfterEachCallback {

    static final Duration TIMEOUT = Duration.ofSeconds(60);

    /**
     * How long the pool must stay idle. A task handed to a new worker thread
     * is neither queued nor active until that thread runs it.
     */
    static final Duration QUIET = Duration.ofMillis(100);

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        ThreadPoolTaskExecutor executor = SpringExtension.getApplicationContext(context)
            .getBean("toolThreadPoolTaskExecutor", ThreadPoolTaskExecutor.class);
        Instant deadline = Instant.now().plus(TIMEOUT);
        Instant idleSince = null;
        while (idleSince == null || Instant.now().isBefore(idleSince.plus(QUIET))) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("validations still running after " + TIMEOUT.toSeconds() + " s");
            }
            boolean idle = executor.getActiveCount() == 0 && executor.getThreadPoolExecutor().getQueue().isEmpty();
            idleSince = idle ? (idleSince == null ? Instant.now() : idleSince) : null;
            Thread.sleep(10);
        }
    }
}
