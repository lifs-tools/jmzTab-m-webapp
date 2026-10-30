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
package org.lifstools.mztab.validator.engine;

import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bounds how many validations and conversions an engine runs at once. A job
 * that finds no free slot is refused at once; the caller answers 503 and the
 * webapp retries.
 */
public class JobLimiter {

    private final Semaphore slots;

    private final int maxConcurrent;

    /**
     * @param maxConcurrent the number of jobs that may run at once
     *     ({@code engine.max-concurrent-jobs})
     */
    public JobLimiter(int maxConcurrent) {
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("engine.max-concurrent-jobs must be at least 1, was " + maxConcurrent);
        }
        this.slots = new Semaphore(maxConcurrent);
        this.maxConcurrent = maxConcurrent;
    }

    /** @return the number of jobs that may run at once */
    public int maxConcurrent() {
        return maxConcurrent;
    }

    /**
     * Takes a free slot without waiting.
     *
     * @return the slot, which closing frees; empty if all slots are taken
     */
    public Optional<Permit> tryAcquire() {
        return slots.tryAcquire() ? Optional.of(new Permit()) : Optional.empty();
    }

    /** The slot of a running job. */
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
}
