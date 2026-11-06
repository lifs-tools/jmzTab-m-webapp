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
package org.lifstools.mztab2.server.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.ServletInputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.DelegatingServletInputStream;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * The size cap holds for a body without a declared length, whichever way the
 * body is read.
 */
class RequestBodiesTest {

    private final RequestBodies bodies = new RequestBodies(DataSize.ofBytes(1024));

    @Test
    void readsABodyUpToTheLimit() throws Exception {
        try (InputStream in = bodies.open(request(1024, false))) {
            assertThat(in.readAllBytes()).hasSize(1024);
        }
    }

    @Test
    void refusesADeclaredLengthOverTheLimitBeforeReading() {
        assertThatThrownBy(() -> bodies.open(request(1025, true)))
            .isInstanceOf(MaxUploadSizeExceededException.class);
    }

    @Test
    void stopsAnUndeclaredBodyOverTheLimitInBulkReads() throws Exception {
        try (InputStream in = bodies.open(request(1025, false))) {
            assertThatThrownBy(in::readAllBytes).isInstanceOf(MaxUploadSizeExceededException.class);
        }
    }

    @Test
    void stopsAnUndeclaredBodyOverTheLimitInSingleByteReads() throws Exception {
        try (InputStream in = RequestBodies.limit(new ByteArrayInputStream(new byte[3]), 2)) {
            assertThat(in.read()).isZero();
            assertThat(in.read()).isZero();
            assertThatThrownBy(in::read).isInstanceOf(MaxUploadSizeExceededException.class);
        }
    }

    /** A request with {@code bytes} of body, with or without a Content-Length. */
    private static MockHttpServletRequest request(int bytes, boolean declared) {
        if (declared) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/rest/v2/validatePlain");
            request.setContent(new byte[bytes]);
            return request;
        }
        // a chunked body: MockHttpServletRequest would declare the length of its content
        return new MockHttpServletRequest("POST", "/rest/v2/validatePlain") {
            @Override
            public ServletInputStream getInputStream() {
                return new DelegatingServletInputStream(new ByteArrayInputStream(new byte[bytes]));
            }
        };
    }
}
