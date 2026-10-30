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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lifstools.mztab.validator.engine.api.ValidationLevel;
import org.lifstools.mztab.validator.engine.api.ValidationMessage;
import org.lifstools.mztab.validator.webapp.engine.EngineRegistry;
import org.lifstools.mztab.validator.webapp.engine.EngineResultReader;
import org.lifstools.mztab.validator.webapp.engine.EngineUnavailableException;
import org.lifstools.mztab.validator.webapp.engine.EnginesProperties;
import org.lifstools.mztab.validator.webapp.validationprofile.ValidationProfileCatalog;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * The synchronous endpoints' way through the engines, against stub engines
 * for 2.0 and 2.1 and the shipped catalog, which has default rules for both lines.
 */
class SynchronousEngineServiceTest {

    static final String OUTCOME = "{\"type\":\"outcome\",\"parsed\":true,\"mzTabVersion\":\"2.1.0-M\",\"messages\":"
        + "[{\"code\":\"c\",\"category\":\"format\",\"message_type\":\"warn\",\"message\":\"from the engine\","
        + "\"line_number\":3}]}\n";
    static final String END = "{\"type\":\"end\",\"sections\":{\"metadataLines\":1}}\n";

    @TempDir
    Path dir;

    private MockWebServer engine20;
    private MockWebServer engine21;
    private SynchronousEngineService service;
    private EnginesProperties properties;

    @BeforeEach
    void start() throws IOException {
        engine20 = new MockWebServer();
        engine21 = new MockWebServer();
        engine20.start();
        engine21.start();
        properties = new EnginesProperties("2.0", List.of(
            new EnginesProperties.Engine("2.0", engine20.url("/").uri()),
            new EnginesProperties.Engine("2.1", engine21.url("/").uri())), Duration.ofSeconds(2),
            Duration.ofSeconds(10), null);
        ValidationProfileCatalog catalog = new ValidationProfileCatalog(new ObjectMapper(), new DefaultResourceLoader(),
            "classpath:/validation-profiles/index.json");
        service = new SynchronousEngineService(new EngineRegistry(properties), new EngineResultReader(null), catalog,
            properties);
    }

    @AfterEach
    void stop() throws IOException {
        engine20.shutdown();
        engine21.shutdown();
    }

    /** Ruling R9: a line without default rules is validated structurally, and the messages say so. */
    @Test
    void semanticValidationOfALineWithoutDefaultRulesIsStructuralWithAnInfo() throws Exception {
        engine21.enqueue(ndjson(OUTCOME + "{\"type\":\"metadata\",\"line\":1,\"key\":\"k\",\"value\":\"v\"}\n" + END));
        Path file = file("MTD\tmzTab-version\t2.1.0-M\n");
        // the shipped catalog has default rules for both lines; this one for 2.0 only
        ValidationProfileCatalog only20 = new ValidationProfileCatalog(new ObjectMapper(), new DefaultResourceLoader(),
            "classpath:/validation-profiles-test/index.json");
        SynchronousEngineService service = new SynchronousEngineService(new EngineRegistry(properties),
            new EngineResultReader(null), only20, properties);

        List<ValidationMessage> messages = service.validate(file, Optional.of("2.1.0-M"), ValidationLevel.INFO, 100,
            true);

        assertThat(messages).extracting(ValidationMessage::getMessage).containsExactly(
            "No default validation rules for mzTab-M 2.1; semantic validation skipped.", "from the engine");
        assertThat(messages.get(0).getMessageType()).isEqualTo(ValidationMessage.MessageType.INFO);
        RecordedRequest request = engine21.takeRequest(5, TimeUnit.SECONDS);
        assertThat(request.getPath()).startsWith("/engine/v1/validations");
        String body = request.getBody().readString(StandardCharsets.UTF_8);
        assertThat(body).contains("name=\"file\"").doesNotContain("name=\"mapping\"");
        assertThat(engine20.getRequestCount()).isZero();
    }

    @Test
    void semanticValidationSendsTheLatestDefaultRulesOfTheLine() throws Exception {
        engine20.enqueue(ndjson(OUTCOME + END));

        service.validate(file("MTD\tmzTab-version\t2.0.0-M\n"), Optional.of("2.0.0-M"), ValidationLevel.INFO, 100,
            true);

        String body = engine20.takeRequest(5, TimeUnit.SECONDS).getBody().readString(StandardCharsets.UTF_8);
        String latest;
        try (var in = getClass().getResourceAsStream("/validation-profiles/mztab-m-2.0-default/1.1.0.xml")) {
            latest = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(body).contains("name=\"mapping\"").contains(latest);
    }

    @Test
    void aFileWithoutVersionGoesToTheDefaultLine() throws Exception {
        engine20.enqueue(ndjson(OUTCOME + END));

        assertThat(service.validate(file("MTD\tmzTab-ID\tx\n"), Optional.empty(), ValidationLevel.INFO, 100, false))
            .extracting(ValidationMessage::getMessage).containsExactly("from the engine");
        assertThat(engine20.getRequestCount()).isEqualTo(1);
    }

    @Test
    void aVersionWithoutEngineReachesNoEngine() throws Exception {
        List<ValidationMessage> messages = service.validate(file("MTD\tmzTab-version\t2.2.0-M\n"),
            Optional.of("2.2.0-M"), ValidationLevel.INFO, 100, true);

        assertThat(messages).extracting(ValidationMessage::getMessage).containsExactly(
            "This file declares mzTab-version 2.2.0-M. This validator supports mzTab-M 2.0 and 2.1.");
        assertThat(messages.get(0).getMessageType()).isEqualTo(ValidationMessage.MessageType.ERROR);
        assertThat(engine20.getRequestCount() + engine21.getRequestCount()).isZero();
    }

    /** An engine that dies mid-answer is unavailable, not a result. */
    @Test
    void aStreamWithoutItsEndIsTheEngineBeingUnavailable() throws Exception {
        engine20.enqueue(ndjson(OUTCOME));

        assertThatThrownBy(() -> service.validate(file("MTD\tmzTab-version\t2.0.0-M\n"), Optional.of("2.0.0-M"),
            ValidationLevel.INFO, 100, false)).isInstanceOf(EngineUnavailableException.class);
    }

    @Test
    void aFileTheEngineRefusesAsTooLargeIsTooLarge() throws Exception {
        engine20.enqueue(new MockResponse().setResponseCode(413).setHeader("Content-Type", "application/json")
            .setBody("{\"code\":\"file_too_large\",\"message\":\"too large\"}"));

        assertThatThrownBy(() -> service.validate(file("MTD\tmzTab-version\t2.0.0-M\n"), Optional.of("2.0.0-M"),
            ValidationLevel.INFO, 100, false)).isInstanceOf(MaxUploadSizeExceededException.class);
    }

    static final String NOT_READ = "{\"type\":\"outcome\",\"parsed\":false,\"mzTabVersion\":null,\"messages\":"
        + "[{\"code\":\"json_not_read\",\"category\":\"format\",\"message_type\":\"error\","
        + "\"message\":\"The JSON file could not be read: Cannot construct instance (line 1, column 14)\","
        + "\"line_number\":-1}]}\n";

    /** The JSON endpoint answers JSON that is not a model as a bad request, as before the engines. */
    @Test
    void jsonThatTheEngineCannotReadIsABadRequestForTheJsonEndpoint() throws Exception {
        engine20.enqueue(ndjson(NOT_READ + END));

        assertThatThrownBy(() -> service.validateJson(file("{\"metadata\": 5}"), Optional.empty(),
            ValidationLevel.INFO, 100, false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("The request body is not mzTab-M JSON: Cannot construct instance (line 1, column 14)");
    }

    /** The file endpoint takes any file and reports what the engine says about it. */
    @Test
    void jsonThatTheEngineCannotReadIsAMessageForTheFileEndpoint() throws Exception {
        engine20.enqueue(ndjson(NOT_READ + END));

        assertThat(service.validate(file("{\"metadata\": 5}"), Optional.empty(), ValidationLevel.INFO, 100, false))
            .extracting(ValidationMessage::getCode).containsExactly(ValidationMessage.JSON_NOT_READ);
    }

    /** Only the code marks the case: an unparsed outcome with another message stays a result. */
    @Test
    void anotherUnparsedOutcomeOfTheJsonEndpointIsAResult() throws Exception {
        engine20.enqueue(ndjson(NOT_READ.replace("json_not_read", "other") + END));

        assertThat(service.validateJson(file("{\"metadata\": 5}"), Optional.empty(), ValidationLevel.INFO, 100,
            false)).extracting(ValidationMessage::getCode).containsExactly("other");
    }

    @Test
    void conversionsRouteByTheDeclaredVersion() throws Exception {
        engine21.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("{\"a\":1}"));

        SynchronousEngineService.Conversion conversion = service.tsvToJson(file("MTD\tmzTab-version\t2.1.0-M\n"));

        Path converted = ((SynchronousEngineService.Conversion.Converted) conversion).file();
        try {
            assertThat(Files.readString(converted)).isEqualTo("{\"a\":1}");
        } finally {
            Files.deleteIfExists(converted);
        }
        assertThat(engine21.takeRequest(5, TimeUnit.SECONDS).getPath()).isEqualTo("/engine/v1/conversions/tsv-to-json");
    }

    @Test
    void aConversionTheEngineRejectsIsRejectedWithItsMessages() throws Exception {
        engine20.enqueue(new MockResponse().setResponseCode(422).setHeader("Content-Type", "application/json")
            .setBody("[{\"code\":\"c\",\"category\":\"format\",\"message_type\":\"error\",\"message\":\"bad\"}]"));

        SynchronousEngineService.Conversion conversion = service.jsonToTsv(
            file("{\"metadata\":{\"mzTab-version\":\"2.0.0-M\"}}"), true);

        assertThat(((SynchronousEngineService.Conversion.Rejected) conversion).messages())
            .extracting(ValidationMessage::getMessage).containsExactly("bad");
        assertThat(engine20.takeRequest(5, TimeUnit.SECONDS).getPath())
            .isEqualTo("/engine/v1/conversions/json-to-tsv?validate=true");
    }

    @Test
    void jsonThatIsNotMzTabIsABadRequest() throws Exception {
        engine20.enqueue(new MockResponse().setResponseCode(400).setHeader("Content-Type", "application/json")
            .setBody("{\"code\":\"not_json\",\"message\":\"The request body is not mzTab-M JSON: x\"}"));

        assertThatThrownBy(() -> service.jsonToTsv(file("{\"metadata\": "), false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("The request body is not mzTab-M JSON: x");
    }

    @Test
    void anMzTab1ConversionReachesNoEngine() throws Exception {
        SynchronousEngineService.Conversion conversion = service.tsvToJson(file("MTD\tmzTab-version\t1.0.0\n"));

        assertThat(((SynchronousEngineService.Conversion.Rejected) conversion).messages())
            .extracting(ValidationMessage::getMessage).containsExactly("This file declares mzTab-version 1.0. "
            + "mzTab 1.0 is no longer supported; the validator checks mzTab-M 2.0 and 2.1 files.");
        assertThat(engine20.getRequestCount() + engine21.getRequestCount()).isZero();
    }

    private Path file(String content) throws IOException {
        return Files.writeString(Files.createTempFile(dir, "upload-", ".mztab"), content, StandardCharsets.UTF_8);
    }

    private static MockResponse ndjson(String body) {
        return new MockResponse().setHeader("Content-Type", "application/x-ndjson").setBody(body);
    }
}
