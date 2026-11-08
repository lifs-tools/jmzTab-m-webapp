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
package org.lifstools.mztab.validator.engine.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CopyIndexTest {

    @Test
    void existingIndexByNameAndNextFree() {
        CopyIndex index = new CopyIndex("MTD\tsample[1]\tP001\nMTD\tsample[3]\tP009\n");
        assertThat(index.indexOf("sample", "P001")).isEqualTo(1);
        assertThat(index.indexOf("sample", "P009")).isEqualTo(3);
        assertThat(index.indexOf("sample", "P002")).isZero();
        assertThat(index.next("sample")).isEqualTo(4);
        assertThat(index.next("assay")).isEqualTo(1);
    }

    @Test
    void allocationKeepsExistingIndexesAndHandsOutFreeOnesInOrder() {
        CopyIndex index = new CopyIndex("MTD\tsample[1]\tP001\nMTD\tsample[3]\tP009\n");
        assertThat(index.allocate("sample", "P009")).isEqualTo(3);
        assertThat(index.allocate("sample", "P002")).isEqualTo(4);
        assertThat(index.allocate("sample", "P003")).isEqualTo(5);
        assertThat(index.allocate("sample", "P002")).isEqualTo(4);
        assertThat(index.reserve("sample")).isEqualTo(6);
        assertThat(index.next("sample")).isEqualTo(7);
    }

    @Test
    void contactsByNameAndRunsByTheirAssay() {
        CopyIndex index = new CopyIndex("""
                MTD\tcontact[2]-name\tJane Doe
                MTD\tms_run[4]-location\tfile:///a.mzML
                MTD\tassay[1]\tP001
                MTD\tassay[1]-ms_run_ref\tms_run[4]
                """);
        assertThat(index.indexOf("contact", "Jane Doe")).isEqualTo(2);
        assertThat(index.indexOf("ms_run", "P001")).isEqualTo(4);
        assertThat(index.indexOf("ms_run", "P002")).isZero();
        assertThat(index.next("ms_run")).isEqualTo(5);
    }

    @Test
    void namesAreComparedAsCellText() {
        CopyIndex index = new CopyIndex("MTD\tsample[2]\t P001  \r\nMTD\tcv[1]-label\tMS\r\n");
        assertThat(index.indexOf("sample", "P001")).isEqualTo(2);
        assertThat(index.cvLabels()).containsExactly("MS");
        assertThat(index.next("cv")).isEqualTo(2);
    }

    @Test
    void metadataLinesAndSections() {
        CopyIndex index = new CopyIndex("""
                MTD\tmzTab-version\t2.0.0-M
                MTD\tsample[1]\tP001
                MTD\tsample[1]-species[1]\t[NCBITaxon, NCBITaxon:9606, Homo sapiens, ]
                MTD\ttitle\tOld

                SMH\tSML_ID
                SML\t1
                """);
        assertThat(index.metadataLine("title")).isEqualTo(4);
        assertThat(index.metadataValue("title")).isEqualTo("Old");
        assertThat(index.metadataLine("description")).isZero();
        assertThat(index.lastMetadataLine("sample")).isEqualTo(3);
        assertThat(index.lastMetadataLine("assay")).isZero();
        assertThat(index.lastMetadataLine()).isEqualTo(4);
        assertThat(index.hasSection("SMH")).isTrue();
        assertThat(index.hasSection("SFH")).isFalse();
        assertThat(index.lineCount()).isEqualTo(7);
        assertThat(index.line(5)).isEmpty();
    }

    @Test
    void emptyText() {
        CopyIndex index = new CopyIndex("");
        assertThat(index.lineCount()).isZero();
        assertThat(index.lastMetadataLine()).isZero();
        assertThat(index.next("sample")).isEqualTo(1);
    }
}
