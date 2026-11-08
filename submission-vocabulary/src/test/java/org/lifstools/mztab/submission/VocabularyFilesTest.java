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
package org.lifstools.mztab.submission;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VocabularyFilesTest {

    private static final String ROOT = "/submission-vocabulary/";
    private static final YAMLMapper YAML = new YAMLMapper();
    private static final ObjectMapper JSON = new ObjectMapper();
    /**
     * The mzTab-M hints that differ from v3 3.0.0, by group and field id: an internal note was dropped from the hint.
     * The hints are informative; the output mapping defines what is written.
     */
    private static final Map<String, String> AMENDED_HINTS = Map.of("lipids.internal_standard", "opt_global_internal_standard");

    private static JsonNode yaml(String path) throws IOException {
        try (InputStream in = VocabularyFilesTest.class.getResourceAsStream(ROOT + path)) {
            assertThat(in).as(path).isNotNull();
            return YAML.readTree(in);
        }
    }

    private static JsonSchema schema(String name) throws IOException {
        try (InputStream in = VocabularyFilesTest.class.getResourceAsStream(ROOT + "schema/" + name)) {
            assertThat(in).as(name).isNotNull();
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(JSON.readTree(in));
        }
    }

    @Test
    void everyFileValidatesAgainstItsSchema() throws IOException {
        assertThat(schema("vocabulary.schema.json").validate(yaml("vocabulary/vocabulary.yaml"))).isEmpty();
        JsonSchema list = schema("list.schema.json");
        List<String> names = Vocabulary.load().listNames();
        assertThat(names).hasSize(31);
        for (String name : names) {
            assertThat(list.validate(yaml("lists/" + name + ".yaml"))).as(name).isEmpty();
        }
    }

    @Test
    void containsEveryV3FieldUnchanged() throws IOException {
        Vocabulary v = Vocabulary.load();
        List<String> expected;
        try (InputStream in = getClass().getResourceAsStream("/v3-field-ids.txt")) {
            expected = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).lines().filter(l -> !l.isBlank()).toList();
        }
        JsonNode vocab = yaml("vocabulary/vocabulary.yaml").get("groups");
        List<String> actual = new ArrayList<>();
        vocab.properties().forEach(g -> {
            if (g.getValue().get("workbook").asBoolean()) {
                g.getValue().get("fields").forEach(f -> actual.add(g.getKey() + "." + f.get("id").asText()));
            }
        });
        assertThat(actual).containsExactlyElementsOf(expected);
        // each field has exactly the keys and values of the v3 schema, apart from the amended mzTab-M hints
        JsonNode v3;
        try (InputStream in = getClass().getResourceAsStream("/v3-sheets.json")) {
            v3 = JSON.readTree(in);
        }
        AMENDED_HINTS.forEach((field, hint) -> {
            String[] gf = field.split("\\.");
            v3.get(gf[0]).forEach(f -> {
                if (f.get("id").asText().equals(gf[1])) {
                    ((ObjectNode) f).put("mztab", hint);
                }
            });
        });
        v3.fields().forEachRemaining(g -> {
            JsonNode fields = vocab.get(g.getKey()).get("fields");
            assertThat(fields.size()).as(g.getKey()).isEqualTo(g.getValue().size());
            for (int i = 0; i < fields.size(); i++) {
                assertThat(fields.get(i)).as(g.getKey() + "#" + i).isEqualTo(g.getValue().get(i));
            }
        });
        assertThat(v.group("quantities").workbook()).isTrue();
        assertThat(v.group("quantities").fields()).isEmpty();
        assertThat(v.group("quantities").help()).startsWith("Layout 'lipids in columns'");
    }

    @Test
    void everyListReferenceResolves() {
        Vocabulary v = Vocabulary.load();
        for (String id : List.of("study", "contacts", "datasets", "samples", "factors", "lipids", "runs")) {
            for (Vocabulary.Field f : v.group(id).fields()) {
                if (f.list() != null) {
                    assertThat(v.list(f.list()).entries()).as(id + "." + f.id()).isNotEmpty();
                }
            }
        }
    }

    @Test
    void runsGroupIsNotRenderedInTheWorkbook() {
        Vocabulary v = Vocabulary.load();
        assertThat(v.group("runs").workbook()).isFalse();
        assertThat(v.group("runs").fields()).extracting(Vocabulary.Field::id).containsExactly(
                "file_name", "file_format", "paired_file", "sample_name", "assay", "fraction_of",
                "scan_polarity", "instrument_name", "dissociation_method");
    }

    @Test
    void molPercentHasItsAgreedEncoding() {
        assertThat(Vocabulary.load().encodingOf("mol% (mole percent of the measured lipidome)"))
                .contains(new Vocabulary.Param("UO", "UO:0000076", "mole fraction", "mol%"));
        assertThat(Vocabulary.load().encodingOf("blank")).isEmpty();
    }

    @Test
    void versionIs100() {
        assertThat(Vocabulary.load().version()).isEqualTo("1.0.0");
    }
}
