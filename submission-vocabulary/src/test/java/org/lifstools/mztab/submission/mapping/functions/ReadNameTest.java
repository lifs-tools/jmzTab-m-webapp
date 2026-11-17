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
package org.lifstools.mztab.submission.mapping.functions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.lifstools.mztab.submission.mapping.MappingRequestException;
import org.lifstools.mztab.submission.mapping.Notes;
import org.lifstools.mztab.submission.mapping.Value;

/** {@code read-name}: the cases of its table in schema/mapping-language.md, and the v3 converter's {@code read_name}. */
class ReadNameTest {

    private static Value call(Value name) {
        return new Functions().call("read-name", List.of(name), new Notes());
    }

    private static List<String> names(String name) {
        Value got = call(new Value.Text(name));
        if (got == Value.Null.INSTANCE) {
            return null;
        }
        return ((Value.ListV) got).value().stream()
                .map(r -> ((Value.Text) ((Value.Obj) r).get("normalised")).value()).toList();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '"', textBlock = """
        PC 34:1                                  | PC 34:1
        PC 37:4 b                                | PC 37:4
        TG 58:4 - (1)                            | TG 58:4
        "BMP 34:2|BMP 16:1_18:1"                 | BMP 16:1_18:1
        "PC 16:0_18:1|PC 34:1|PC 16:0_18:1"      | PC 16:0_18:1
        SM(d18:1_14:0);SM(d16:1_16:0)            | SM 18:1;O2/14:0 ; SM 16:1;O2/16:0
        PE(14:0_14:0)_B;PE(12:0_16:0)            | PE 14:0_14:0 ; PE 12:0_16:0
        Cer d18:1_16:0                           | Cer 18:1;O2/16:0
        SM 18:1;O2                               | SM 18:1;O2
        """)
    void readsTheNameReadingByReading(String name, String expected) {
        assertThat(names(name)).containsExactly(expected.split(" ; "));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '"', textBlock = """
        "PCp(35:5)|PE O-38:6"
        PC 34:1;unknown
        "  "
        """)
    void aReadingThatIsNotReadGivesNull(String name) {
        assertThat(names(name)).isNull();
    }

    @Test
    void aReadingIsAGoslinParse() {
        Value got = call(new Value.Text("PC 34:1"));
        assertThat(got).isEqualTo(new Value.ListV(List.of(
                new Functions().call("goslin-parse", List.of(new Value.Text("PC 34:1")), new Notes()))));
    }

    @Test
    void theSphingoidRewriteCountsOnlyForASphingolipid() {
        assertThat(names("PC(d16:0_18:1)")).isNull();
    }

    @Test
    @Timeout(5)
    void aLongNameOrOneWithTooManyPartsIsNotRead() {
        String many = String.join("|", java.util.Collections.nCopies(ReadName.MAX_PARTS + 1, "PC 34:1"));
        assertThat(names(many)).isNull();
        assertThat(names(String.join("|", java.util.Collections.nCopies(ReadName.MAX_PARTS, "PC 34:1")))).containsExactly("PC 34:1");
        assertThat(names(String.join(";", java.util.Collections.nCopies(ReadName.MAX_PARTS + 1, "PC 34:1")))).isNull();
        String longName = "PC 34:1" + " ".repeat(StripNameNotes.MAX_LENGTH);
        assertThat(names(longName)).isNull();
        names("|".repeat(StripNameNotes.MAX_LENGTH));
        names("x;".repeat(StripNameNotes.MAX_LENGTH / 2));
    }

    @Test
    void nullGivesNullAndOtherValuesAreAnError() {
        assertThat(call(Value.Null.INSTANCE)).isEqualTo(Value.Null.INSTANCE);
        assertThatThrownBy(() -> call(new Value.Int(3))).isInstanceOf(MappingRequestException.class);
    }
}
