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
import org.lifstools.mztab.submission.Vocabulary.Param;

class RenderTest {

    @Test
    void param() {
        assertThat(Render.param(new Param("", "", "chromatography column", "Waters Acquity UPLC BEH C18, 2.1 x 100 mm, 1.7 um")))
                .isEqualTo("[, , chromatography column, \"Waters Acquity UPLC BEH C18, 2.1 x 100 mm, 1.7 um\"]");
        assertThat(Render.param(new Param("MS", "MS:1003966", "LipidSearch", "4.2"))).isEqualTo("[MS, MS:1003966, LipidSearch, 4.2]");
        assertThat(Render.param(new Param("NCIT", "", "x", ""))).isEqualTo("[, , x, ]");
        assertThat(Render.param(new Param("", "", "", "v"))).isNull();
        assertThat(Render.param(new Param("", "", " \t", "v"))).isNull();
        assertThat(Render.param(new Param("NCIT", "NCIT:C28421", "", "female"))).isEqualTo("[NCIT, NCIT:C28421, , female]");
    }

    @Test
    void quoted() {
        assertThat(Render.quoted("say \"hi\",\tµmol/L\n")).isEqualTo("\"say 'hi', µmol/L\"");
        assertThat(Render.quoted("Ärzte  und\r\nPatienten")).isEqualTo("Ärzte und Patienten");
    }

    @Test
    void textCollapsesPythonWhitespace() {
        assertThat(Render.text("  a \t b\n\nc  ")).isEqualTo("a b c");
        // str.split() also splits at NO-BREAK SPACE, U+001C..U+001F and U+0085
        assertThat(Render.text("a b\u001fc\u0085d　e")).isEqualTo("a b c d e");
        assertThat(Render.text(" \n ")).isEmpty();
    }
}
