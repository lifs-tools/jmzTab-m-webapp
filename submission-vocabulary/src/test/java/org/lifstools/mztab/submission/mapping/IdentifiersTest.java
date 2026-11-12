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
package org.lifstools.mztab.submission.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The parametrised cases of lipidcompass-submissions bb8dcbf {@code tests/unit/test_identifiers.py}. */
class IdentifiersTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "10.1234/abc.5",
        " 10.1234/abc.5 ",
        "doi:10.1234/abc.5",
        "DOI: 10.1234/abc.5",
        "doi:doi:10.1234/abc.5",
        "https://doi.org/10.1234/abc.5",
        "doi:https://doi.org/10.1234/abc.5",
        "http://dx.doi.org/10.1234/abc.5/",
        "doi:http://dx.doi.org/10.1234/abc.5",
        "10.1234/abc.5.",
    })
    void doiStripsPrefixesAndTrailingPunctuation(String raw) {
        assertThat(Identifiers.doi(raw)).isEqualTo("10.1234/abc.5");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "nan", "xxxx", "unpublished", "doi:", "10.12/too-short-registrant",
        "10.1002/(SICI)1097:4", "https://example.org/paper"})
    void doiIsNullForWhatJmzTabMWouldReject(String raw) {
        assertThat(Identifiers.doi(raw)).isNull();
    }

    @Test
    void bareDoiKeepsCase() {
        assertThat(Identifiers.bareDoi("doi:10.1234/ABC")).isEqualTo("10.1234/ABC");
    }

    @ParameterizedTest
    @ValueSource(strings = {"jane.doe@uni-x.de", "j_d-o@lab.example.org", "o.o'neil@x.ie"})
    void validEmail(String email) {
        assertThat(Identifiers.email(email)).isTrue();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "uni-x.de", "@uni-x.de", "jane@", "jane doe@uni-x.de", "jane@uni-x", "jane@@uni-x.de", "nan"})
    void invalidEmail(String email) {
        assertThat(Identifiers.email(email)).isFalse();
    }
}
