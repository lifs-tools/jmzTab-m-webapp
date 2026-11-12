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
package org.lifstools.mztab.validator.webapp.vocabulary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** The controller's own refusal of paths, without the firewall of Spring Security in front of it. */
class SubmissionVocabularyControllerUnitTest {

    private final SubmissionVocabularyController controller = new SubmissionVocabularyController();

    @ParameterizedTest
    @ValueSource(strings = {"/vocabulary/../../application.yml", "/vocabulary/./vocabulary.yaml",
        "/vocabulary//vocabulary.yaml", "/vocabulary/../LICENSE", "/src/x", "/vocabulary/a b", "/vocabulary/x\\y",
        "/vocabulary/vocabulary.yaml/"})
    void otherPathsAreBadRequests(String path) {
        assertThatThrownBy(() -> controller.file("1.1.0", path)).isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/examples/v3-example", "/examples/v3-example/expected"})
    void directoriesAreNotFound(String path) {
        assertThatThrownBy(() -> controller.file("1.1.0", path)).isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/vocabulary/vocabulary.yaml", "/LICENSE"})
    void aFileIsServed(String path) {
        assertThat(controller.file("1.1.0", path).getBody()).isNotEmpty();
    }
}
