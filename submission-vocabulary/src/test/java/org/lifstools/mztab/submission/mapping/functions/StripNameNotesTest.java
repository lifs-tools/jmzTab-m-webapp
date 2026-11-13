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

/** {@code strip-name-notes}: the cases of its table in schema/mapping-language.md, and the v3 converter's. */
class StripNameNotesTest {

    private static Value call(Value name) {
        return new Functions().call("strip-name-notes", List.of(name), new Notes());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '"', textBlock = """
        TAG(10:0/14:1/18:0)_M+NH4                                       | TAG(10:0/14:1/18:0)
        PC(16:0_18:1)_M+CH3COO //  M+CH3COO                             | PC(16:0_18:1)
        TG(48:0) [NL-16:0]                                              | TG(48:0)
        SM C14:0 (BIOCRATES kit notation                                | SM C14:0
        Cer 18:0;3/16:0;0 (the migration wrote 'Cer 18:0;(OH)3/16:0')   | Cer 18:0;3/16:0;0
        LPC(17:1) (a) [sn1] [104_sn1]                                   | LPC(17:1)
        CerH2O(d18:1_16:0)_A                                            | CerH2O(d18:1_16:0)
        PC 34:1 [M+H]+                                                  | PC 34:1
        PC(16:0/18:1)                                                   | PC(16:0/18:1)
        Cer(d18:1/18:0(OH))                                             | Cer(d18:1/18:0(OH))
        TG 48:2[NL-14:0]                                                | TG 48:2
        "PC 34:1 (a) "                                                  | PC 34:1
        """)
    void removesTheNotesAtTheEnd(String name, String expected) {
        assertThat(call(new Value.Text(name))).isEqualTo(new Value.Text(expected));
    }

    @Test
    void pythonWhitespaceCountsBeforeAComment() {
        assertThat(call(new Value.Text("PC 34:1 (note) "))).isEqualTo(new Value.Text("PC 34:1"));
        assertThat(call(new Value.Text("PC 34:1​(note)"))).isEqualTo(new Value.Text("PC 34:1​(note)"));
    }

    @Test
    void aNameLongerThanTheLimitIsReturnedAsItIs() {
        String name = "PC 34:1" + " [a]".repeat(StripNameNotes.MAX_LENGTH / 4);
        assertThat(name.length()).isGreaterThan(StripNameNotes.MAX_LENGTH);
        assertThat(call(new Value.Text(name))).isEqualTo(new Value.Text(name));
        String atLimit = "PC 34:1" + " [a]".repeat((StripNameNotes.MAX_LENGTH - 7) / 4);
        assertThat(atLimit.length()).isBetween(StripNameNotes.MAX_LENGTH - 3, StripNameNotes.MAX_LENGTH);
        assertThat(call(new Value.Text(atLimit))).isEqualTo(new Value.Text("PC 34:1"));
    }

    @Test
    @Timeout(2)
    void adversarialNamesAtTheLimitStayCheap() {
        int n = StripNameNotes.MAX_LENGTH;
        for (String name : List.of(" ".repeat(n - 1) + "x", "[M+".repeat(n / 3), "x" + " (".repeat(n / 2 - 1),
                "x" + " [a]".repeat(n / 4 - 1), "x" + "\u00a0".repeat(n - 2) + "[", "_M+".repeat(n / 3))) {
            call(new Value.Text(name));
        }
    }

    @Test
    void nullGivesNullAndOtherValuesAreAnError() {
        assertThat(call(Value.Null.INSTANCE)).isEqualTo(Value.Null.INSTANCE);
        assertThatThrownBy(() -> call(new Value.Int(3))).isInstanceOf(MappingRequestException.class);
    }
}
