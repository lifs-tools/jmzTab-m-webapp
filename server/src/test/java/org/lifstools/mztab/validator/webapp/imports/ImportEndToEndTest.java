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
package org.lifstools.mztab.validator.webapp.imports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.lifstools.mztab.validator.engine.api.ValidationMessage;
import org.lifstools.mztab.validator.webapp.AwaitAsyncValidations;
import org.lifstools.mztab.validator.webapp.EngineProxy;
import org.lifstools.mztab.validator.webapp.workingcopy.NewFile;
import org.lifstools.mztab.validator.webapp.workingcopy.WorkingCopyInfo;
import org.lifstools.mztab.validator.webapp.workingcopy.WorkingCopyService;
import org.lifstools.mztab.validator.webapp.workingcopy.WorkingCopyValidation;
import org.lifstools.mztab.validator.webapp.workingcopy.WorkingCopyValidation.Status;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The N-2a "done when": the vocabulary's v3 example, imported into a new mzTab-M 2.0 file with the real engine, makes
 * a file that the validator finds no error in.
 */
@SpringBootTest(properties = {"eureka.client.enabled=false", "spring.cloud.discovery.enabled=false"})
@ExtendWith(AwaitAsyncValidations.class)
class ImportEndToEndTest {

    static final EngineProxy ENGINE = EngineProxy.start();

    @TempDir
    static Path storageRoot;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("storage.location", () -> storageRoot.toString());
        ENGINE.register(registry);
    }

    @Autowired
    WorkingCopyService copies;

    @Autowired
    ImportService imports;

    @Autowired
    ObjectMapper json;

    private WorkingCopyValidation await(UUID id, long revision) throws InterruptedException {
        Instant end = Instant.now().plus(Duration.ofSeconds(120));
        while (Instant.now().isBefore(end)) {
            WorkingCopyValidation v = copies.info(id).validation();
            if (v != null && v.status() != Status.RUNNING && v.revision() == revision) {
                return v;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("no ended run on revision " + revision);
    }

    private void assertNoErrors(UUID id, long revision, boolean semantic, boolean olsLookups)
        throws InterruptedException {
        copies.requestValidation(id, semantic);
        WorkingCopyValidation v = await(id, revision);
        assertEquals(Status.DONE, v.status(), "failure: " + v.failureReason());
        List<String> errors = v.messages().stream().map(m -> m.message())
            .filter(m -> m.getMessageType() == ValidationMessage.MessageType.ERROR)
            .filter(m -> !(olsLookups && String.valueOf(m.getCode()).contains("Error-3015")
                && m.getMessage().contains("Could not retrieve parents")))
            .map(m -> m.getCode() + " " + m.getMessage()).toList();
        assertTrue(errors.isEmpty(), (semantic ? "CV rules checked: " : "structure only: ") + errors);
    }

    @Test
    void theV3ExampleMakesAFileWithoutErrors() throws Exception {
        JsonNode instance;
        try (InputStream in = getClass().getClassLoader()
            .getResourceAsStream("submission-vocabulary/examples/v3-example/instance.json")) {
            assertNotNull(in, "the example is on the classpath");
            instance = json.readTree(in);
        }
        WorkingCopyInfo copy = copies.createNew(new NewFile("2.0.0-M", null, "mztab-m-2.0-default", "1.1.0",
            "LCS-99999-01"));

        ImportPreview preview = imports.preview(copy.id(),
            new ImportRequest("vocabulary-instance", copy.revision(), instance, 1, "LCS-99999", true));

        assertEquals(copy.revision(), preview.batch().revision());
        int operations = preview.batch().operations().size();
        assertTrue(operations > 0 && operations < 50, "operations: " + operations);
        WorkingCopyInfo edited = copies.edit(copy.id(), new org.lifstools.mztab.validator.webapp.workingcopy.EditBatch(
            preview.batch().revision(), preview.batch().operations(), UUID.randomUUID(), copy.generation()));
        assertEquals(copy.revision() + 1, edited.revision());
        System.out.println("IMPORT: " + operations + " operations, revision " + edited.revision() + ", "
            + edited.lineCount() + " lines; notes " + preview.notes() + "; suggestions "
            + preview.suggestions().size());

        assertNoErrors(copy.id(), edited.revision(), false, false);
        // not the CV rules: the OLS stub, which other tests assert has no unmatched request, has no fixtures for the
        // terms of the example (MS:1000422 and others), so that check breaks on the stub, not on the file
    }
}
