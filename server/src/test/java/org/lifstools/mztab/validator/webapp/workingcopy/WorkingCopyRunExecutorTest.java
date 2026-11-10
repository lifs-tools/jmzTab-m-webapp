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
package org.lifstools.mztab.validator.webapp.workingcopy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.lifstools.mztab.validator.engine.api.ValidationLevel;
import org.lifstools.mztab.validator.webapp.AwaitAsyncValidations;
import org.lifstools.mztab.validator.webapp.EngineProxy;
import org.lifstools.mztab.validator.webapp.WebConfig;
import org.lifstools.mztab.validator.webapp.session.ValidationOptions;
import org.lifstools.mztab.validator.webapp.session.ValidationSessionService;
import org.lifstools.mztab.validator.webapp.session.ValidationStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Runs of working copies have a pool of their own, with a bounded queue (here one thread and one queued run), so
 * that a person editing neither delays the validation of an upload nor queues without limit.
 */
@SpringBootTest(properties = {"eureka.client.enabled=false", "spring.cloud.discovery.enabled=false",
    "workingcopy.runs.threads=1", "workingcopy.runs.queue=1"})
@ExtendWith(AwaitAsyncValidations.class)
class WorkingCopyRunExecutorTest {

    static final EngineProxy ENGINE = EngineProxy.start();
    private static final ValidationOptions STRUCTURAL = new ValidationOptions(ValidationLevel.INFO, 100, null, null);
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

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
    ValidationSessionService sessions;

    @Autowired
    @Qualifier("workingCopyRunExecutor")
    Executor runExecutor;

    @Autowired
    @Qualifier("toolThreadPoolTaskExecutor")
    Executor validationExecutor;

    @AfterEach
    void forward() {
        ENGINE.reset();
    }

    private UUID submit(String name) throws IOException, InterruptedException {
        byte[] content;
        try (InputStream in = getClass().getResourceAsStream("/metabolomics/lipidomics-example.mzTab")) {
            content = in.readAllBytes();
        }
        UUID id = sessions.submit(name, new ByteArrayInputStream(content), STRUCTURAL, null).id();
        awaitEnd(id);
        return id;
    }

    private void awaitEnd(UUID id) throws InterruptedException {
        Instant end = Instant.now().plus(TIMEOUT);
        while (Instant.now().isBefore(end)) {
            ValidationStatus s = sessions.find(id).orElseThrow().status();
            if (s == ValidationStatus.FINISHED || s == ValidationStatus.FAILED) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("validation " + id + " did not end");
    }

    @Test
    void copyRunsDoNotUseTheValidationPool() {
        WorkingCopyService target = AopTestUtils.getTargetObject(copies);
        Object used = ReflectionTestUtils.getField(target, "executor");
        assertNotNull(used);
        assertNotSame(validationExecutor, used);
        assertSame(runExecutor, used);
    }

    @Test
    void aFullRunQueueAnswers503AndLeavesTheCopyAsItWas() throws Exception {
        UUID a = submit("a.mzTab");
        UUID b = submit("b.mzTab");
        UUID c = submit("c.mzTab");
        copies.create(a, SourceEncoding.UTF_8);
        copies.create(b, SourceEncoding.UTF_8);
        copies.create(c, SourceEncoding.UTF_8);

        EngineProxy.Gate held = ENGINE.holdNextValidation();
        copies.requestValidation(a, false);
        assertTrue(held.awaitArrival(TIMEOUT), "the first run holds the only thread");
        copies.requestValidation(b, false);

        WorkingCopyBusyException busy = assertThrows(WorkingCopyBusyException.class,
            () -> copies.requestValidation(c, false));
        assertTrue(busy.getMessage().contains("try again"), busy.getMessage());
        WorkingCopyValidation shown = copies.info(c).validation();
        assertTrue(shown == null || shown.status() != WorkingCopyValidation.Status.RUNNING,
            "a refused request shows no running check: " + shown);

        held.release();
        awaitRunsEnded(a, b);
        // room again: the refused copy can be checked now
        copies.requestValidation(c, false);
        awaitRunsEnded(c);
    }

    @Test
    void uploadValidationsStartWhileCopiesRun() throws Exception {
        UUID a = submit("a.mzTab");
        UUID b = submit("b.mzTab");
        copies.create(a, SourceEncoding.UTF_8);
        copies.create(b, SourceEncoding.UTF_8);

        EngineProxy.Gate held = ENGINE.holdNextValidation();
        copies.requestValidation(a, false);
        assertTrue(held.awaitArrival(TIMEOUT));
        copies.requestValidation(b, false);
        // the copy pool is full: one run active, one queued

        UUID upload = submit("upload.mzTab");
        assertEquals(ValidationStatus.FINISHED, sessions.find(upload).orElseThrow().status());
        held.release();
        awaitRunsEnded(a, b);
    }

    @Test
    void theSizesAreValidatedAtStartup() {
        WebConfig config = new WebConfig();
        assertThrows(IllegalArgumentException.class, () -> config.workingCopyRunExecutor(0, 16));
        assertThrows(IllegalArgumentException.class, () -> config.workingCopyRunExecutor(2, 0));
        assertThrows(IllegalArgumentException.class, () -> config.workingCopyRunExecutor(-1, -1));
    }

    private void awaitRunsEnded(UUID... ids) throws InterruptedException {
        Instant end = Instant.now().plus(TIMEOUT);
        for (UUID id : ids) {
            while (true) {
                WorkingCopyValidation v = copies.info(id).validation();
                if (v != null && v.status() != WorkingCopyValidation.Status.RUNNING) {
                    break;
                }
                if (Instant.now().isAfter(end)) {
                    throw new AssertionError("the run of " + id + " did not end");
                }
                Thread.sleep(50);
            }
        }
    }
}
