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
package org.lifstools.mztab.validator.webapp.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.lifstools.mztab.validator.engine.api.EngineError;
import org.lifstools.mztab.validator.engine.api.EngineErrorCode;
import org.lifstools.mztab.validator.engine.api.EngineInfo;
import org.lifstools.mztab.validator.engine.api.ValidationLevel;
import org.lifstools.mztab.validator.engine.api.ValidationMessage;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.lang.Nullable;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Talks to the engine of one version line over its HTTP API
 * ({@code /engine/v1}). Uploads are streamed from files or streams, never
 * buffered; the callers own and close the streams they pass.
 */
@Slf4j
public class EngineClient {

    private static final MediaType NDJSON = MediaType.parseMediaType("application/x-ndjson");
    private static final MediaType TSV = MediaType.parseMediaType("text/tab-separated-values");
    private static final Duration DEFAULT_RETRY_AFTER = Duration.ofSeconds(5);
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final String line;
    /** For jobs: the read timeout is the job timeout. */
    private final RestClient jobs;
    /** For asking the engine about itself: the read timeout is the connect timeout. */
    private final RestClient probes;

    public EngineClient(String line, URI baseUrl, Duration connectTimeout, Duration jobTimeout) {
        this.line = line;
        // HTTP/1.1: no h2c upgrade attempt on plain http
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(connectTimeout).build();
        this.jobs = RestClient.builder().baseUrl(baseUrl.toString()).requestFactory(factory(http, jobTimeout)).build();
        this.probes = RestClient.builder().baseUrl(baseUrl.toString())
            .requestFactory(factory(http, connectTimeout)).build();
    }

    private static JdkClientHttpRequestFactory factory(HttpClient http, Duration readTimeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(readTimeout);
        return factory;
    }

    /** The version line this client talks to. */
    public String line() {
        return line;
    }

    /**
     * Asks the engine about itself.
     *
     * @throws EngineUnavailableException if it cannot be reached or answers
     * anything but its info
     */
    public EngineInfo info() {
        return call(probes.get().uri("/engine/v1/info").accept(MediaType.APPLICATION_JSON), response -> {
            if (response.getStatusCode().value() != 200) {
                throw unexpected(response);
            }
            return MAPPER.readValue(response.getBody(), EngineInfo.class);
        });
    }

    /**
     * Validates a file and hands the NDJSON response stream to the reader,
     * which must consume it within the call.
     *
     * @param file the mzTab-M file, streamed from disk
     * @param mapping an optional CV mapping file
     * @param ndjsonReader reads the response; an {@link UncheckedIOException}
     * it throws (the stream died) is reported as the engine being unavailable
     */
    public <T> T validate(Path file, @Nullable Path mapping, ValidationLevel level, int maxErrors,
        Function<InputStream, T> ndjsonReader) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("file", part(file, "upload.mztab"));
        if (mapping != null) {
            parts.add("mapping", part(mapping, "mapping.xml"));
        }
        RestClient.RequestHeadersSpec<?> request = jobs.post()
            .uri(b -> b.path("/engine/v1/validations").queryParam("level", level.name())
                .queryParam("maxErrors", maxErrors).build())
            .contentType(MediaType.MULTIPART_FORM_DATA).accept(NDJSON).body(parts);
        return call(request, response -> {
            if (response.getStatusCode().value() != 200) {
                throw mapError(response);
            }
            return ndjsonReader.apply(response.getBody());
        });
    }

    /**
     * Converts tab separated mzTab-M to JSON.
     *
     * @return the target, now holding the JSON
     */
    public Path tsvToJson(InputStream tsv, Path target) {
        RestClient.RequestHeadersSpec<?> request = jobs.post().uri("/engine/v1/conversions/tsv-to-json")
            .contentType(TSV).accept(MediaType.APPLICATION_JSON).body(new InputStreamResource(tsv));
        return convert(request, target);
    }

    /**
     * Converts mzTab-M JSON to tab separated mzTab-M.
     *
     * @param validate whether the engine parses its output again and reports
     * errors (as {@link EngineRejection})
     * @return the target, now holding the tab separated file
     */
    public Path jsonToTsv(InputStream json, boolean validate, Path target) {
        RestClient.RequestHeadersSpec<?> request = jobs.post()
            .uri(b -> b.path("/engine/v1/conversions/json-to-tsv").queryParam("validate", validate).build())
            .contentType(MediaType.APPLICATION_JSON).accept(TSV).body(new InputStreamResource(json));
        return convert(request, target);
    }

    private Path convert(RestClient.RequestHeadersSpec<?> request, Path target) {
        try {
            return call(request, response -> {
                if (response.getStatusCode().value() != 200) {
                    throw mapError(response);
                }
                Files.copy(response.getBody(), target, StandardCopyOption.REPLACE_EXISTING);
                return target;
            });
        } catch (RuntimeException e) {
            try {
                Files.deleteIfExists(target);
            } catch (IOException ignored) {
                // nothing to add to the original failure
            }
            throw e;
        }
    }

    @FunctionalInterface
    private interface Handler<T> {

        T handle(ClientHttpResponse response) throws IOException;
    }

    /** Runs a request; connection problems, timeouts and a dying stream are the engine being unavailable. */
    private <T> T call(RestClient.RequestHeadersSpec<?> request, Handler<T> handler) {
        try {
            return request.exchange((req, res) -> handler.handle(res), true);
        } catch (ResourceAccessException e) {
            log.debug("The engine for mzTab-M {} could not be reached: {}", line, e.toString());
            throw new EngineUnavailableException(line, e);
        } catch (UncheckedIOException e) {
            log.warn("The response of the engine for mzTab-M {} ended unexpectedly: {}", line, e.toString());
            throw new EngineUnavailableException(line, e);
        }
    }

    private static FileSystemResource part(Path file, String filename) {
        return new FileSystemResource(file) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
    }

    private RuntimeException unexpected(ClientHttpResponse response) throws IOException {
        log.warn("The engine for mzTab-M {} answered HTTP {}", line, response.getStatusCode().value());
        return new EngineUnavailableException(line);
    }

    private RuntimeException mapError(ClientHttpResponse response) throws IOException {
        int status = response.getStatusCode().value();
        switch (status) {
            case 503:
                return new EngineBusyException(retryAfter(response));
            case 422:
                try {
                    return new EngineRejection(MAPPER.readValue(response.getBody(),
                        new TypeReference<List<ValidationMessage>>() { }));
                } catch (IOException e) {
                    log.warn("The engine for mzTab-M {} answered HTTP 422 with an unreadable body", line);
                    return new EngineUnavailableException(line, e);
                }
            case 400:
            case 413:
                return new EngineRequestException(readError(response, status));
            default:
                return unexpected(response);
        }
    }

    private static EngineError readError(ClientHttpResponse response, int status) {
        try {
            EngineError error = MAPPER.readValue(response.getBody(), EngineError.class);
            if (error != null && error.code() != null && error.message() != null) {
                return error;
            }
        } catch (IOException | RuntimeException e) {
            // fall through: the body is not an engine error
        }
        return new EngineError(status == 413 ? EngineErrorCode.FILE_TOO_LARGE : EngineErrorCode.INVALID_REQUEST,
            "The engine refused the request (HTTP " + status + ").");
    }

    private static Duration retryAfter(ClientHttpResponse response) {
        String header = response.getHeaders().getFirst("Retry-After");
        if (header != null) {
            try {
                long seconds = Long.parseLong(header.strip());
                if (seconds >= 0 && seconds <= 3600) {
                    return Duration.ofSeconds(seconds);
                }
            } catch (NumberFormatException e) {
                // an HTTP date or garbage: use the default
            }
        }
        return DEFAULT_RETRY_AFTER;
    }
}
