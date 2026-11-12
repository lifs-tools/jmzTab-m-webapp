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
package org.lifstools.mztab.submission;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The vocabulary inside a jar, used by several threads at once, as the packaged engine does. */
class VocabularyConcurrencyTest {

    private static final int THREADS = 8;
    private static final String MOL_PERCENT = "mol% (mole percent of the measured lipidome)";

    @Test
    void concurrentFirstUseInsideAJarSucceeds(@TempDir Path dir) throws Exception {
        Path jar = jarOfVocabulary(dir.resolve("vocabulary.jar"));
        // a fresh loader per round, so that no zip file system is open before the threads start
        for (int round = 0; round < 5; round++) {
            try (URLClassLoader loader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, null)) {
                List<Optional<Vocabulary.Param>> results = concurrently(() -> {
                    Vocabulary v = Vocabulary.load(loader);
                    v.listNames();
                    return v.encodingOf(MOL_PERCENT);
                });
                assertThat(results).hasSize(THREADS)
                        .allSatisfy(r -> assertThat(r).contains(new Vocabulary.Param("UO", "UO:0000076", "mole fraction", "mol%")));
            }
        }
    }

    @Test
    void concurrentEncodingLookupsOnOneVocabularyAgree(@TempDir Path dir) throws Exception {
        Path jar = jarOfVocabulary(dir.resolve("vocabulary.jar"));
        try (URLClassLoader loader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, null)) {
            Vocabulary v = Vocabulary.load(loader);
            List<Optional<Vocabulary.Param>> results = concurrently(() -> v.encodingOf(MOL_PERCENT));
            assertThat(results).hasSize(THREADS).allSatisfy(r -> assertThat(r).isPresent());
        }
    }

    private static <T> List<T> concurrently(java.util.concurrent.Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> out = new ArrayList<>();
            for (Future<T> f : futures) {
                out.add(f.get()); // rethrows a thread's exception
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    /** A jar holding the {@code submission-vocabulary/} resources, with directory entries as Maven writes them. */
    private static Path jarOfVocabulary(Path jar) throws IOException, URISyntaxException {
        Path classes = Path.of(VocabularyConcurrencyTest.class.getResource("/submission-vocabulary/vocabulary/vocabulary.yaml").toURI())
                .getParent().getParent().getParent();
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream zip = new JarOutputStream(out);
                Stream<Path> files = Files.walk(classes.resolve("submission-vocabulary"))) {
            for (Path p : files.sorted().toList()) {
                String name = classes.relativize(p).toString().replace('\\', '/');
                if (Files.isDirectory(p)) {
                    zip.putNextEntry(new JarEntry(name + "/"));
                } else {
                    zip.putNextEntry(new JarEntry(name));
                    Files.copy(p, zip);
                }
                zip.closeEntry();
            }
        }
        return jar;
    }
}
