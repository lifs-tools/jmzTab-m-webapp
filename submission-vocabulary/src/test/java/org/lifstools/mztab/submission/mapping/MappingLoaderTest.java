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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MappingLoaderTest {

    private static final String HEAD = "id: t\nversion: 1.0.0\nkind: output\nvocabulary: 1.0.0\ntarget: 2.0.0-M\n";

    private static Mapping parse(String yaml) {
        return MappingLoader.read(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "test.yaml");
    }

    @Test
    void minimalMappingLoads() {
        Mapping m = MappingLoader.read("mapping-test/minimal.yaml");
        assertThat(m.id()).isEqualTo("minimal");
        assertThat(m.version()).isEqualTo("1.0.0");
        assertThat(m.kind()).isEqualTo(Mapping.Kind.OUTPUT);
        assertThat(m.vocabulary()).isEqualTo("1.0.0");
        assertThat(m.target()).isEqualTo("2.0.0-M");
        assertThat(m.parameters()).containsExactly("study_id", "dataset_index");
        assertThat(m.cvs()).containsExactly(java.util.Map.entry("NCBITaxon",
                new Mapping.CvInfo("NCBI organismal classification", "2026-07-12", "http://purl.obolibrary.org/obo/ncbitaxon.owl")));
        assertThat(m.let()).containsOnlyKeys("dataset");
        assertThat(m.metadata()).hasSize(3);
        assertThat(m.metadata().get(0)).isEqualTo(new Mapping.Line("mzTab-version", new Expr.Literal(new Value.Text("2.0.0-M"))));
        assertThat(m.metadata().get(2)).isInstanceOfSatisfying(Mapping.Each.class, e -> {
            assertThat(e.as()).isEqualTo("s");
            assertThat(e.where()).isNotNull();
            assertThat(e.lines()).hasSize(1);
        });
        assertThat(m.tables()).singleElement().satisfies(t -> {
            assertThat(t.section()).isEqualTo("SML");
            assertThat(t.header()).isEqualTo("SMH");
            assertThat(t.as()).isEqualTo("l");
            assertThat(t.columns()).hasSize(2);
            assertThat(t.columns().get(1)).isInstanceOf(Mapping.EachColumn.class);
        });
    }

    @Test
    void unknownExpressionKeyNamesItsPointer() {
        assertThatThrownBy(() -> MappingLoader.read("mapping-test/unknown-form.yaml"))
                .isInstanceOf(InvalidMappingException.class)
                .hasMessageContaining("/metadata/0/value")
                .hasMessageContaining("bogus");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        // two keys in one expression node
        "metadata: [{key: k, value: {literal: a, field: b}}]|/metadata/0/value",
        // pos of a variable no each binds
        "metadata: [{key: k, value: {pos: x}}]|/metadata/0/value/pos",
        // a key placeholder no each binds
        "metadata: [{each: {literal: [1]}, as: s, lines: [{key: 'sample[{t#}]', value: {pos: s}}]}]|/metadata/0/lines/0/key",
        // a let name that does not exist
        "metadata: [{key: k, value: {ref: nope}}]|/metadata/0/value/ref",
        // an invalid regular expression
        "metadata: [{key: k, value: {match: {value: {literal: a}, regex: '(', group: g}}}]|/metadata/0/value/match/regex",
        // a format argument that is not given
        "metadata: [{key: k, value: {format: {template: '{1}', args: [{literal: a}]}}}]|/metadata/0/value/format/template",
        // YAML 1.1 reads an unquoted yes as a boolean, which a literal does not take
        "metadata: [{key: k, value: {eq: [{field: f.primary.text}, {literal: yes}]}}]|/metadata/0/value/eq/1/literal",
        // a function outside the documented set
        "metadata: [{key: k, value: {fn: {name: shell, args: []}}}]|/metadata/0/value/fn/name",
        // a column header placeholder outside an each
        "tables: [{section: SML, header: SMH, rows: {literal: []}, as: r, columns: [{header: 'a[{r#}]', value: {pos: r}}]}]|/tables/0/columns/0/header",
    })
    void invalidMappingsNameThePointer(String body, String pointer) {
        assertThatThrownBy(() -> parse(HEAD + body + "\n")).isInstanceOf(InvalidMappingException.class).hasMessageContaining(pointer);
    }

    @Test
    void anOutputMappingNeedsATargetAndAnInputMappingHasNone() {
        String input = "id: t\nversion: 1.0.0\nkind: input\nvocabulary: 1.0.0\n";
        assertThat(parse(input).kind()).isEqualTo(Mapping.Kind.INPUT);
        assertThatThrownBy(() -> parse(input + "target: 2.0.0-M\n")).isInstanceOf(InvalidMappingException.class)
                .hasMessageContaining("/target");
        assertThatThrownBy(() -> parse(input.replace("kind: input", "kind: output"))).isInstanceOf(InvalidMappingException.class)
                .hasMessageContaining("target");
    }

    @Test
    void idsMayHoldUpperCase() {
        assertThat(parse(HEAD.replace("id: t", "id: mztab-2.0.0-M")).id()).isEqualTo("mztab-2.0.0-M");
    }

    @Test
    void duplicateKeysAreRejected() {
        assertThatThrownBy(() -> parse(HEAD + "metadata: [{key: k, value: {literal: a}, value: {literal: b}}]\n"))
                .isInstanceOf(InvalidMappingException.class).hasMessageContaining("value");
    }

    @Test
    void loadByIdRejectsUnknownAndUnsafeIds() {
        assertThatThrownBy(() -> MappingLoader.load("no-such-mapping")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no-such-mapping");
        assertThatThrownBy(() -> MappingLoader.load("../vocabulary/vocabulary")).isInstanceOf(IllegalArgumentException.class);
    }
}
