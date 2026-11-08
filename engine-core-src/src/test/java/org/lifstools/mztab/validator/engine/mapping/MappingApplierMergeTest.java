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
import static org.lifstools.mztab.validator.engine.mapping.MappingApplierTest.HOMO_SAPIENS;
import static org.lifstools.mztab.validator.engine.mapping.MappingApplierTest.TWO_ZERO;
import static org.lifstools.mztab.validator.engine.mapping.MappingApplierTest.applier;
import static org.lifstools.mztab.validator.engine.mapping.MappingApplierTest.request;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.lifstools.mztab.validator.engine.api.LineEdit;
import org.lifstools.mztab.validator.engine.api.MappingApplyResult;
import org.lifstools.mztab.validator.engine.api.Suggestion;
import org.lifstools.mztab.validator.engine.api.SuggestionState;

/** Applying {@code mapping/merge.yaml} to a copy that already has a sample, an assay, a run, a study variable and MS. */
class MappingApplierMergeTest {

    static final String INSTANCE = "{\"study\":{\"title\":\"New title\"},"
            + "\"contacts\":[{\"name\":\"Jane Doe\",\"email\":\"jane@example.org\"}],"
            + "\"samples\":[{\"sample_name\":\"P001\",\"species\":" + HOMO_SAPIENS + ",\"raw\":\"file:///P001.mzML\"},"
            + "{\"sample_name\":\"P002\",\"species\":" + HOMO_SAPIENS + ",\"raw\":\"file:///P002.mzML\"}],"
            + "\"groups\":[{\"name\":\"all\",\"members\":[\"P001\",\"P002\"]}],"
            + "\"lipids\":[{\"name\":\"PC 34:1\",\"values\":[1.5,2.5]},{\"name\":\"PE 36:2\",\"values\":[null,4]}]}";

    static final String SPECIES = "[NCBITaxon, NCBITaxon:9606, Homo sapiens, ]";

    static final String METADATA = """
            MTD\tmzTab-version\t2.0.0-M
            MTD\tmzTab-ID\tOLD-01
            MTD\ttitle\tOld
            MTD\tcontact[1]-name\tJane Doe
            MTD\tcontact[1]-email\told@example.org
            MTD\tsample[1]\tP009
            MTD\tsample[1]-species[1]\t%1$s
            MTD\tms_run[1]-location\tfile:///P009.mzML
            MTD\tassay[1]\tP009
            MTD\tassay[1]-sample_ref\tsample[1]
            MTD\tassay[1]-ms_run_ref\tms_run[1]
            MTD\tstudy_variable[1]\told group
            MTD\tstudy_variable[1]-assay_refs\tassay[1]
            MTD\tcv[1]-label\tMS
            MTD\tcv[1]-full_name\tPSI-MS controlled vocabulary
            MTD\tcv[1]-version\t4.2.2
            MTD\tcv[1]-uri\thttp://purl.obolibrary.org/obo/ms.owl
            """.formatted(SPECIES);

    static final String SML = """

            SMH\tSML_ID\tchemical_name\tabundance_assay[1]
            SML\t1\tPC 34:1\t7
            """;

    static final String MERGED_METADATA = """
            MTD\tmzTab-version\t2.0.0-M
            MTD\tmzTab-ID\tOLD-01
            MTD\ttitle\tNew title
            MTD\tcontact[1]-name\tJane Doe
            MTD\tcontact[1]-email\tjane@example.org
            MTD\tsample[1]\tP009
            MTD\tsample[1]-species[1]\t%1$s
            MTD\tsample[2]\tP001
            MTD\tsample[2]-species[1]\t%1$s
            MTD\tsample[3]\tP002
            MTD\tsample[3]-species[1]\t%1$s
            MTD\tms_run[1]-location\tfile:///P009.mzML
            MTD\tms_run[2]-location\tfile:///P001.mzML
            MTD\tms_run[2]-scan_polarity[1]\t[MS, MS:1000130, positive scan, ]
            MTD\tms_run[3]-location\tfile:///P002.mzML
            MTD\tms_run[3]-scan_polarity[1]\t[MS, MS:1000130, positive scan, ]
            MTD\tassay[1]\tP009
            MTD\tassay[1]-sample_ref\tsample[1]
            MTD\tassay[1]-ms_run_ref\tms_run[1]
            MTD\tassay[2]\tP001
            MTD\tassay[2]-sample_ref\tsample[2]
            MTD\tassay[2]-ms_run_ref\tms_run[2]
            MTD\tassay[3]\tP002
            MTD\tassay[3]-sample_ref\tsample[3]
            MTD\tassay[3]-ms_run_ref\tms_run[3]
            MTD\tstudy_variable[1]\told group
            MTD\tstudy_variable[1]-assay_refs\tassay[1]
            MTD\tstudy_variable[2]\tall
            MTD\tstudy_variable[2]-assay_refs\tassay[2]|assay[3]
            MTD\tcv[1]-label\tMS
            MTD\tcv[1]-full_name\tPSI-MS controlled vocabulary
            MTD\tcv[1]-version\t4.2.2
            MTD\tcv[1]-uri\thttp://purl.obolibrary.org/obo/ms.owl
            MTD\tcv[2]-label\tNCBITaxon
            MTD\tcv[2]-full_name\tNCBI organismal classification
            MTD\tcv[2]-version\t2026-07-12
            MTD\tcv[2]-uri\thttp://purl.obolibrary.org/obo/ncbitaxon.owl
            """.formatted(SPECIES);

    @Test
    void newElementsTakeFreeIndexesAndEveryReferenceAgrees() {
        MappingApplyResult r = applier().apply(request("merge", INSTANCE, METADATA + SML, false), TWO_ZERO);
        assertThat(r.text()).isEqualTo(MERGED_METADATA + SML);
        assertThat(r.suggestions()).contains(
                new Suggestion("sample[2]", "P001", SuggestionState.NEW),
                new Suggestion("assay[2]-sample_ref", "sample[2]", SuggestionState.NEW),
                new Suggestion("study_variable[2]-assay_refs", "assay[2]|assay[3]", SuggestionState.NEW),
                new Suggestion("mzTab-version", "2.0.0-M", SuggestionState.UNCHANGED),
                new Suggestion("contact[1]-name", "Jane Doe", SuggestionState.UNCHANGED));
        assertThat(r.suggestions()).extracting(Suggestion::key).doesNotContain("cv[1]-label", "sample[1]")
                .containsOnlyOnce("cv[2]-label");
    }

    @Test
    void anExistingSingletonOrMatchedElementIsReplacedInPlace() {
        MappingApplyResult r = applier().apply(request("merge", INSTANCE, METADATA + SML, false), TWO_ZERO);
        assertThat(r.edits()).startsWith(new LineEdit.SetCell(3, 2, "New title"),
                new LineEdit.SetCell(5, 2, "jane@example.org"));
        assertThat(r.suggestions()).contains(new Suggestion("title", "New title", SuggestionState.REPLACES_EXISTING),
                new Suggestion("contact[1]-email", "jane@example.org", SuggestionState.REPLACES_EXISTING));
    }

    @Test
    void anExistingTableSectionConflictsAndIsNotTouched() {
        String copy = METADATA + SML;
        MappingApplyResult r = applier().apply(request("merge", INSTANCE, copy, false), TWO_ZERO);
        List<String> lines = copy.lines().toList();
        for (LineEdit e : r.edits()) {
            switch (e) {
                case LineEdit.InsertLine i -> assertThat(i.text()).startsWith("MTD\t");
                case LineEdit.SetCell s -> assertThat(lines.get((int) s.line() - 1)).startsWith("MTD");
                case LineEdit.InsertColumn c -> throw new AssertionError("no column is inserted: " + c);
            }
        }
        List<Suggestion> table = r.suggestions().stream().filter(s -> s.key().startsWith("SM")).toList();
        assertThat(table).extracting(Suggestion::key).containsExactly("SMH", "SML[1]", "SML[2]");
        assertThat(table).allMatch(s -> s.state() == SuggestionState.CONFLICTS);
        assertThat(r.notes()).containsExactly(
                "SML already exists; table imports into an existing section come with the summary-table import");
    }

    @Test
    void aNewTableSectionUsesTheAllocatedAssayIndexes() {
        MappingApplyResult r = applier().apply(request("merge", INSTANCE, METADATA, false), TWO_ZERO);
        assertThat(r.text()).isEqualTo(MERGED_METADATA + "\nSMH\tSML_ID\tchemical_name\tabundance_assay[2]\tabundance_assay[3]\n"
                + "SML\t1\tPC 34:1\t1.5\t2.5\nSML\t2\tPE 36:2\tnull\t4\n");
    }
}
