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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lifstools.mztab.validator.engine.api.ValidationLevel;
import org.lifstools.mztab.validator.webapp.EngineProxy;
import org.lifstools.mztab.validator.webapp.sections.Section;
import org.lifstools.mztab.validator.webapp.sections.TableQuery;
import org.lifstools.mztab.validator.webapp.session.ValidationOptions;
import org.lifstools.mztab.validator.webapp.session.ValidationSessionService;
import org.lifstools.mztab.validator.webapp.session.ValidationStatus;
import org.lifstools.mztab.validator.webapp.workingcopy.EditOperation.InsertColumn;
import org.lifstools.mztab.validator.webapp.workingcopy.EditOperation.SetCell;
import org.lifstools.mztab.validator.webapp.workingcopy.WorkingCopyValidation.Status;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Measures the working-copy operations on a file of the largest real shape
 * (see {@link LargeFiles}) against the budgets of design section 9. Excluded
 * from the default build; run with
 * {@code ./mvnw -B -pl server -am verify -Dfrontend.skip=true -Dgroups=performance
 * -Dtest=LargeFilePerformanceTest -Dsurefire.failIfNoSpecifiedTests=false}.
 * Every measurement is printed; the budgets are asserted together at the end,
 * so one slow step does not hide the others. The structural validation by the
 * real 2.0 engine is reported, not asserted.
 */
@Tag("performance")
@SpringBootTest(properties = {"eureka.client.enabled=false", "spring.cloud.discovery.enabled=false"})
class LargeFilePerformanceTest {

    static final EngineProxy ENGINE = EngineProxy.start();
    private static final ValidationOptions STRUCTURAL = new ValidationOptions(ValidationLevel.INFO, 100, null, null);
    private static final Duration LONG = Duration.ofMinutes(20);

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
    WorkingCopyReader reader;

    @Autowired
    ValidationSessionService sessions;

    private final List<String> report = new ArrayList<>();
    private final List<String> failures = new ArrayList<>();

    private <T> T measure(String what, long budgetMs, Supplier<T> work) {
        long start = System.nanoTime();
        T result = work.get();
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        String budget = budgetMs < 0 ? "reported, not asserted" : "budget " + budgetMs + " ms";
        report.add(String.format("%-48s %8d ms  (%s)", what, ms, budget));
        if (budgetMs >= 0 && ms > budgetMs) {
            failures.add(what + ": " + ms + " ms > " + budgetMs + " ms");
        }
        return result;
    }

    private static EditBatch batch(long revision, EditOperation... ops) {
        return new EditBatch(revision, List.of(ops));
    }

    @Test
    void largeFileStaysWithinBudgets() throws Exception {
        Path file = storageRoot.resolve("large.mzTab");
        long generate = System.nanoTime();
        LargeFiles.write(file);
        long bytes = Files.size(file);
        report.add(String.format("generated %d bytes (%d lines) in %d ms", bytes, Files.lines(file).count(),
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - generate)));

        UUID id;
        try (var in = Files.newInputStream(file)) {
            id = sessions.submit("large.mzTab", in, STRUCTURAL, null).id();
        }
        // not measured: the session's own validation, so that it does not compete with the steps below
        Instant end = Instant.now().plus(LONG);
        long sessionStart = System.nanoTime();
        while (true) {
            ValidationStatus s = sessions.find(id).orElseThrow().status();
            if (s == ValidationStatus.FINISHED || s == ValidationStatus.FAILED) {
                report.add("session validation of the upload ended " + s + " after "
                    + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sessionStart) + " ms (not asserted)");
                break;
            }
            assertTrue(Instant.now().isBefore(end), "the session validation did not end");
            Thread.sleep(200);
        }

        WorkingCopyInfo info = measure("create a copy from the upload", 10_000,
            () -> copies.create(id, SourceEncoding.UTF_8));
        long lines = LargeFiles.METADATA_LINES + 1 + 1 + LargeFiles.ROWS;
        assertEquals(lines, info.lineCount());

        measure("lines page (200 lines, SML rows)", 300, () -> reader.lines(id, lines - 199, 200));
        measure("lines page (200 lines, metadata)", 300, () -> reader.lines(id, 1000, 200));
        measure("table page (SML, 50 rows, 1,990 columns)", 300, () -> reader.table(id, Section.SML,
            new TableQuery(0, 50, null, false, List.of(), List.of())).orElseThrow());
        measure("table page (SML, 50 rows, sorted)", 300, () -> reader.table(id, Section.SML,
            new TableQuery(3, 50, "SML_ID", true, List.of(), List.of())).orElseThrow());
        measure("metadata groups", 300, () -> reader.metadataGroups(id));

        long headerLine = LargeFiles.METADATA_LINES + 2;
        long[] revision = {info.revision()};
        // the editor sends one SetCell per edit; the larger batch is reported for comparison
        measure("one SetCell batch (1 cell)", 100, () -> {
            WorkingCopyInfo after = copies.edit(id, batch(revision[0], new SetCell(headerLine + 1, 20, "1234.5")));
            revision[0] = after.revision();
            return after;
        });
        measure("one SetCell batch (100 cells)", -1, () -> {
            List<EditOperation> ops = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                ops.add(new SetCell(headerLine + 1 + (i % LargeFiles.ROWS), 20 + i, "1234." + i));
            }
            WorkingCopyInfo after = copies.edit(id, new EditBatch(revision[0], ops));
            revision[0] = after.revision();
            return after;
        });
        measure("InsertColumn across all rows", 2_000, () -> {
            WorkingCopyInfo after = copies.edit(id, batch(revision[0],
                new InsertColumn("SMH", 14, "opt_global_inserted", "1")));
            revision[0] = after.revision();
            return after;
        });

        Path assembled = storageRoot.resolve("assembled.mzTab");
        measure("assembling the text for validation", 3_000, () -> {
            try (OutputStream out = Files.newOutputStream(assembled)) {
                copies.writeTsv(id, out);
                return null;
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        assertTrue(Files.size(assembled) > bytes, "the inserted column made the text longer");

        copies.requestValidation(id, false);
        long validationStart = System.nanoTime();
        WorkingCopyValidation v;
        while (true) {
            v = copies.info(id).validation();
            if (v != null && v.status() != Status.RUNNING && v.revision() == revision[0]) {
                break;
            }
            assertTrue(Instant.now().isBefore(end.plus(LONG)), "the working-copy validation did not end");
            Thread.sleep(200);
        }
        report.add(String.format("%-48s %8d ms  (reported, not asserted; status %s, parsed %s, %d messages)",
            "one structural validation on the 2.0 engine",
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - validationStart), v.status(), v.parsed(),
            v.messages().size()));
        v.messages().stream().limit(5).forEach(m -> report.add("    " + m.message().getMessage()));

        System.out.println("=== LargeFilePerformanceTest (" + System.getProperty("os.arch") + ", "
            + Runtime.getRuntime().availableProcessors() + " CPUs, java " + System.getProperty("java.version")
            + ") ===\n" + String.join("\n", report) + "\n=== end ===");
        assertTrue(failures.isEmpty(), "budgets exceeded: " + failures);
    }
}
