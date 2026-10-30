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

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.Supplier;

/**
 * Passes at most {@code limit} bytes through and refuses the rest, so an
 * upload of unknown length cannot exceed a cap.
 */
public final class CappedInputStream extends FilterInputStream {

    private final long limit;
    private final Supplier<? extends RuntimeException> exceeded;
    private long count;

    /**
     * @param exceeded the exception to throw once more than {@code limit}
     * bytes are read
     */
    public CappedInputStream(InputStream in, long limit, Supplier<? extends RuntimeException> exceeded) {
        super(in);
        this.limit = limit;
        this.exceeded = exceeded;
    }

    /** The number of bytes read so far. */
    public long count() {
        return count;
    }

    @Override
    public int read() throws IOException {
        int b = super.read();
        if (b >= 0) {
            add(1);
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int n = super.read(b, off, len);
        if (n > 0) {
            add(n);
        }
        return n;
    }

    @Override
    public long skip(long n) throws IOException {
        throw new IOException("skip is not supported");
    }

    @Override
    public boolean markSupported() {
        return false;
    }

    private void add(int n) {
        count += n;
        if (count > limit) {
            throw exceeded.get();
        }
    }
}
