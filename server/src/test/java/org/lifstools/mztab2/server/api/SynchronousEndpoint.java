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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The endpoints that validate or convert a file on the request thread, with
 * a small request for each.
 */
enum SynchronousEndpoint {

    VALIDATE("/rest/v2/validate", MediaType.APPLICATION_JSON, MediaType.APPLICATION_JSON),
    VALIDATE_PLAIN("/rest/v2/validatePlain", ConvertApiIntegrationTest.TSV, MediaType.APPLICATION_JSON),
    CONVERT("/rest/v2/convert", MediaType.APPLICATION_JSON, ConvertApiIntegrationTest.TSV),
    CONVERT_PLAIN("/rest/v2/convertPlain", ConvertApiIntegrationTest.TSV, MediaType.APPLICATION_JSON);

    final String path;

    final MediaType contentType;

    final MediaType accept;

    SynchronousEndpoint(String path, MediaType contentType, MediaType accept) {
        this.path = path;
        this.contentType = contentType;
        this.accept = accept;
    }

    /** A request with a small file in this endpoint's format. */
    MockHttpServletRequestBuilder request() {
        return request(MediaType.APPLICATION_JSON.equals(contentType) ? ConvertApiIntegrationTest.METADATA_ONLY
            : "MTD\tmzTab-version\t2.0.0-M");
    }

    /** A request with a file of at least {@code bytes} bytes in this endpoint's format. */
    MockHttpServletRequestBuilder request(int bytes) {
        return request(MediaType.APPLICATION_JSON.equals(contentType)
            ? "{\"metadata\": {\"description\": \"" + "x".repeat(bytes) + "\"}}"
            : "COM\t" + "x".repeat(bytes));
    }

    private MockHttpServletRequestBuilder request(String body) {
        return post(path).contentType(contentType).accept(accept).content(body);
    }
}
