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
package org.lifstools.mztab.validator.webapp.service.validation;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Bounds the number of synchronous validations and conversions that run at
 * once. {@code /rest/v2/validate}, {@code /validatePlain}, {@code /convert}
 * and {@code /convertPlain} answer on a request thread and hold a whole file
 * and its model; without a bound, parallel requests can exhaust the memory.
 * Validations through {@code /rest/v2/validations} run on their own pool.
 */
@Component
public class SynchronousLimiter {

    static final String DEFAULT_MAX = "#{T(java.lang.Runtime).getRuntime().availableProcessors()}";

    public static final String BUSY = "Too many files are being validated or converted. Please try again shortly.";

    private final Semaphore slots;

    private final Duration wait;

    /**
     * @param maxConcurrent the number of requests that may run at once
     * @param wait how long a request waits for a free slot
     */
    @Autowired
    public SynchronousLimiter(@Value("${rest.synchronous.max-concurrent:" + DEFAULT_MAX + "}") int maxConcurrent,
        @Value("${rest.synchronous.wait:10s}") Duration wait) {
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("rest.synchronous.max-concurrent must be at least 1, was " + maxConcurrent);
        }
        this.slots = new Semaphore(maxConcurrent, true);
        this.wait = wait;
    }

    /**
     * Takes a slot, waiting up to {@code rest.synchronous.wait} for one.
     *
     * @return the slot; closing it frees the slot
     * @throws BusyException if no slot became free in time
     */
    public Permit acquire() {
        try {
            if (slots.tryAcquire(wait.toMillis(), TimeUnit.MILLISECONDS)) {
                return new Permit();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        throw new BusyException();
    }

    /** A slot of a running request. */
    public final class Permit implements AutoCloseable {

        private final AtomicBoolean released = new AtomicBoolean();

        private Permit() {
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                slots.release();
            }
        }
    }

    /** No slot became free in time. */
    public static class BusyException extends RuntimeException {

        public BusyException() {
            super(BUSY);
        }
    }
}
