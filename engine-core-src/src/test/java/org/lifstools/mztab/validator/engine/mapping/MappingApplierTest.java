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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.lifstools.mztab.submission.mapping.MappingLoader;
import org.lifstools.mztab.validator.engine.api.LineEdit;
import org.lifstools.mztab.validator.engine.api.MappingApplyRequest;
import org.lifstools.mztab.validator.engine.api.MappingApplyResult;
import org.lifstools.mztab.validator.engine.api.Suggestion;
import org.lifstools.mztab.validator.engine.api.SuggestionState;

class MappingApplierTest {

    static final Set<String> TWO_ZERO = Set.of("2.0.0-M");
    static final ObjectMapper JSON = new ObjectMapper();
    static final String HOMO_SAPIENS = "{\"cv\":\"NCBITaxon\",\"accession\":\"NCBITaxon:9606\",\"name\":\"Homo sapiens\"}";

    /** Reads the test mappings from {@code src/test/resources/mapping/<id>.yaml}. */
    static MappingApplier applier() {
        return new MappingApplier(id -> MappingLoader.read("mapping/" + id + ".yaml"));
    }

    static JsonNode json(String s) {
        try {
            return JSON.readTree(s);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    static MappingApplyRequest request(String mapping, String instance, String text, boolean provenance) {
        return new MappingApplyRequest(mapping, "1.0.0", Map.of(), json(instance), text, provenance, null);
    }

    @Test
    void anEmptyTextBecomesTheWholeFile() {
        MappingApplyResult r = applier().apply(request("tiny",
                "{\"samples\":[{\"sample_name\":\"P001\",\"species\":" + HOMO_SAPIENS + "}]}", "", false), TWO_ZERO);
        assertThat(r.text()).isEqualTo(
                "MTD\tmzTab-version\t2.0.0-M\nMTD\tsample[1]\tP001\nMTD\tsample[1]-species[1]\t[NCBITaxon, NCBITaxon:9606, Homo sapiens, ]\n"
                + "MTD\tcv[1]-label\tNCBITaxon\nMTD\tcv[1]-full_name\tNCBI organismal classification\nMTD\tcv[1]-version\t2026-07-12\n"
                + "MTD\tcv[1]-uri\thttp://purl.obolibrary.org/obo/ncbitaxon.owl\n");
        assertThat(r.edits()).hasSize(7).allMatch(e -> e instanceof LineEdit.InsertLine);
        assertThat(r.edits().get(0)).isEqualTo(new LineEdit.InsertLine(1, "MTD\tmzTab-version\t2.0.0-M"));
        assertThat(r.edits().get(6)).isEqualTo(new LineEdit.InsertLine(7, "MTD\tcv[1]-uri\thttp://purl.obolibrary.org/obo/ncbitaxon.owl"));
        assertThat(r.suggestions()).hasSize(7).allMatch(s -> s.state() == SuggestionState.NEW);
        assertThat(r.suggestions().get(1)).isEqualTo(new Suggestion("sample[1]", "P001", SuggestionState.NEW));
        assertThat(r.notes()).isEmpty();
    }

    @Test
    void aValueThatRendersEmptyWritesNoLine() {
        MappingApplyResult r = applier().apply(request("tiny", "{\"study\":{\"description\":\" \\t \"},\"samples\":[]}", "", false),
                TWO_ZERO);
        assertThat(r.text()).isEqualTo("MTD\tmzTab-version\t2.0.0-M\n");
    }

    @Test
    void aMappingForAnotherVersionIsRefused() {
        assertThatThrownBy(() -> applier().apply(request("tiny", "{}", "", false), Set.of("2.1.0-M")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("mapping tiny targets 2.0.0-M; this engine serves 2.1.0-M");
    }

    @Test
    void anUnknownMappingVersionIsRefused() {
        MappingApplyRequest r = new MappingApplyRequest("tiny", "9.9.9", Map.of(), json("{}"), "", false, null);
        assertThatThrownBy(() -> applier().apply(r, TWO_ZERO)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("mapping tiny has version 1.0.0, not 9.9.9");
    }

    @Test
    void unknownCvsAreDeclaredAfterTheMappingsOnesSortedWithANote() {
        MappingApplyResult r = applier().apply(request("tiny", "{\"samples\":["
                + "{\"sample_name\":\"P1\",\"species\":{\"cv\":\"ZFA\",\"accession\":\"ZFA:1\",\"name\":\"z\"}},"
                + "{\"sample_name\":\"P2\",\"species\":{\"cv\":\"BTO\",\"accession\":\"BTO:1\",\"name\":\"b\"}},"
                + "{\"sample_name\":\"P3\",\"species\":" + HOMO_SAPIENS + "}]}", "", false), TWO_ZERO);
        assertThat(r.text()).endsWith("MTD\tcv[1]-label\tNCBITaxon\nMTD\tcv[1]-full_name\tNCBI organismal classification\n"
                + "MTD\tcv[1]-version\t2026-07-12\nMTD\tcv[1]-uri\thttp://purl.obolibrary.org/obo/ncbitaxon.owl\n"
                + "MTD\tcv[2]-label\tBTO\nMTD\tcv[2]-full_name\tBTO\nMTD\tcv[2]-version\tunknown\nMTD\tcv[2]-uri\tnull\n"
                + "MTD\tcv[3]-label\tZFA\nMTD\tcv[3]-full_name\tZFA\nMTD\tcv[3]-version\tunknown\nMTD\tcv[3]-uri\tnull\n");
        assertThat(r.notes()).containsExactly(
                "cv 'BTO' has no version or URI in the mapping tiny; add it to the mapping's cvs",
                "cv 'ZFA' has no version or URI in the mapping tiny; add it to the mapping's cvs");
    }

    @Test
    void provenanceListsTheWebappWithTheVocabularyAndTheMapping() {
        MappingApplyResult r = applier().apply(request("tiny",
                "{\"samples\":[{\"sample_name\":\"P001\",\"species\":" + HOMO_SAPIENS + "}]}", "", true), TWO_ZERO);
        List<String> lines = r.text().lines().toList();
        assertThat(lines.get(3)).matches("MTD\tsoftware\\[1\\]\t\\[, , jmzTab-m-webapp, \\d+\\.\\d+\\.\\d+[^\\]]*\\]");
        assertThat(lines.subList(4, 7)).containsExactly("MTD\tsoftware[1]-setting[1]\tvocabulary 1.0.0",
                "MTD\tsoftware[1]-setting[2]\ttiny 1.0.0", "MTD\tcv[1]-label\tNCBITaxon");
    }

    @Test
    void aTabOrLineBreakThatWouldReachACellIsRefused() {
        // a parameter's cv and accession are written as given, so they are the way an instance value reaches a cell raw
        assertThatThrownBy(() -> applier().apply(request("tiny", "{\"samples\":[{\"sample_name\":\"P001\",\"species\":"
                + "{\"cv\":\"NCBITaxon\",\"accession\":\"NCBITaxon:9606\\tx\",\"name\":\"Homo sapiens\"}}]}", "", false), TWO_ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("sample[1]-species[1]: value contains a tab or line break");
        assertThatThrownBy(() -> applier().apply(request("tiny", "{\"samples\":[{\"sample_name\":\"P001\",\"species\":"
                + "{\"cv\":\"NCBI\\nTaxon\",\"accession\":\"NCBITaxon:9606\",\"name\":\"Homo sapiens\"}}]}", "", false), TWO_ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("sample[1]-species[1]: value contains a tab or line break");
    }

    @Test
    void aTabOrLineBreakInATextIsWrittenAsTheConverterWritesIt() {
        // texts are rendered as conv:112-114 does, " ".join(s.split()), so a sample name with a tab and a title with a
        // line break are written with a space, not refused
        MappingApplyResult r = applier().apply(request("merge", MappingApplierMergeTest.INSTANCE
                .replace("\"P001\"", "\"P\\t001\"").replace("New title", "New\\ntitle"), "", false), TWO_ZERO);
        assertThat(r.text()).contains("MTD\tsample[1]\tP 001\n", "MTD\ttitle\tNew title\n", "MTD\tassay[1]-sample_ref\tsample[1]\n");
    }

    @Test
    void provenanceListsTheInputMappingAsTheThirdSetting() {
        MappingApplyRequest request = new MappingApplyRequest("tiny", "1.0.0", Map.of(), json("{\"samples\":[]}"), "", true,
                "v3-workbook 1.0.0");
        MappingApplyResult r = applier().apply(request, TWO_ZERO);
        assertThat(r.text().lines().skip(2).toList()).containsExactly("MTD\tsoftware[1]-setting[1]\tvocabulary 1.0.0",
                "MTD\tsoftware[1]-setting[2]\ttiny 1.0.0", "MTD\tsoftware[1]-setting[3]\tv3-workbook 1.0.0");
    }

    @Test
    void provenanceTakesTheNextFreeSoftwareIndexAndReusesTheWebappsEntry() {
        String webapp = "[, , jmzTab-m-webapp, " + Provenance.webappVersion() + "]";
        MappingApplyResult first = applier().apply(request("tiny", "{\"samples\":[]}",
                "MTD\tmzTab-version\t2.0.0-M\nMTD\tsoftware[1]\t[, , other, 1]\n", true), TWO_ZERO);
        assertThat(first.text()).isEqualTo("MTD\tmzTab-version\t2.0.0-M\nMTD\tsoftware[1]\t[, , other, 1]\n"
                + "MTD\tsoftware[2]\t" + webapp + "\nMTD\tsoftware[2]-setting[1]\tvocabulary 1.0.0\n"
                + "MTD\tsoftware[2]-setting[2]\ttiny 1.0.0\n");
        MappingApplyResult again = applier().apply(request("tiny", "{\"samples\":[]}", first.text(), true), TWO_ZERO);
        assertThat(again.edits()).isEmpty();
        assertThat(again.text()).isEqualTo(first.text());
        assertThat(again.suggestions()).allMatch(s -> s.state() == SuggestionState.UNCHANGED);
    }

    @Test
    void aNewTableFollowsMetadataAfterABlankLine() {
        MappingApplyResult r = applier().apply(request("merge", MappingApplierMergeTest.INSTANCE, "", false), TWO_ZERO);
        assertThat(r.text()).contains("MTD\tcv[2]-uri\thttp://purl.obolibrary.org/obo/ncbitaxon.owl\n\n"
                + "SMH\tSML_ID\tchemical_name\tabundance_assay[1]\tabundance_assay[2]\n"
                + "SML\t1\tPC 34:1\t1.5\t2.5\nSML\t2\tPE 36:2\tnull\t4\n");
        assertThat(r.text()).endsWith("SML\t2\tPE 36:2\tnull\t4\n");
        assertThat(r.suggestions()).contains(new Suggestion("SMH", "SML_ID\tchemical_name\tabundance_assay[1]\tabundance_assay[2]",
                SuggestionState.NEW), new Suggestion("SML[2]", "2\tPE 36:2\tnull\t4", SuggestionState.NEW));
    }
}
