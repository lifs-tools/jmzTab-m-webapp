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
package org.lifstools.mztab.validator.webapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The 2.1 engine is skipped only when the build left it out (no {@code engine21.jar} property, i.e.
 * {@code -DskipSnapshotEngines}); a property that names a jar which is not there (e.g. {@code -pl server} without
 * {@code -am}) must fail the tests, not skip them silently.
 */
class EngineTestSupportTest {

    @TempDir
    Path dir;

    @Test
    void noPropertyMeansTheBuildLeftTheEngineOut() {
        assertThat(EngineTestSupport.has21(null)).isFalse();
        assertThat(EngineTestSupport.has21("")).isFalse();
        assertThat(EngineTestSupport.has21("  ")).isFalse();
    }

    @Test
    void anExistingJarIsThere() throws Exception {
        Path jar = Files.createFile(dir.resolve("engine-2.1.jar"));
        assertThat(EngineTestSupport.has21(jar.toString())).isTrue();
    }

    @Test
    void aMissingJarFailsWithTheCommandToBuildIt() {
        Path missing = dir.resolve("missing.jar");
        assertThatThrownBy(() -> EngineTestSupport.has21(missing.toString()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(missing.toString())
            .hasMessageContaining("-am")
            .hasMessageContaining("skipSnapshotEngines");
    }
}
