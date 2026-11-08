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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.lifstools.mztab.submission.Vocabulary;
import org.lifstools.mztab.submission.Vocabulary.Param;

class EvaluatorTest {

    private static final Vocabulary VOCABULARY = Vocabulary.load();
    private static final YAMLMapper YAML = new YAMLMapper();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Value NULL = Value.Null.INSTANCE;

    private final Notes notes = new Notes();

    private static Expr expr(String yaml) {
        try {
            return MappingLoader.expression(YAML.readTree(yaml));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static JsonNode json(String json) {
        try {
            return JSON.readTree(json);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private Value eval(String yaml) {
        return eval(yaml, "{}");
    }

    private Value eval(String yaml, String instance) {
        return eval(yaml, instance, Map.of());
    }

    private Value eval(String yaml, String instance, Map<String, String> parameters) {
        return new Evaluator(Map.of(), VOCABULARY, Evaluator.FunctionRegistry.NONE)
                .eval(expr(yaml), Scope.root(json(instance), parameters, notes));
    }

    private static Value text(String s) {
        return new Value.Text(s);
    }

    private static Value list(Value... items) {
        return new Value.ListV(List.of(items));
    }

    private static Value obj(Object... keysAndValues) {
        LinkedHashMap<String, Value> m = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            m.put((String) keysAndValues[i], (Value) keysAndValues[i + 1]);
        }
        return new Value.Obj(m);
    }

    @Test
    void literal() {
        assertThat(eval("literal: abc")).isEqualTo(text("abc"));
        assertThat(eval("literal: 3")).isEqualTo(new Value.Int(3));
        assertThat(eval("literal: 3.5")).isEqualTo(new Value.Dbl(3.5));
        assertThat(eval("literal: null")).isSameAs(NULL);
        assertThat(eval("literal: [a, {b: 1}]")).isEqualTo(list(text("a"), obj("b", new Value.Int(1))));
    }

    @Test
    void field() {
        String instance = "{\"study\": {\"title\": \"T\", \"n\": 45, \"bmi\": 29.7, \"none\": null}}";
        assertThat(eval("field: study.title", instance)).isEqualTo(text("T"));
        assertThat(eval("field: study.n", instance)).isEqualTo(new Value.Int(45));
        assertThat(eval("field: study.bmi", instance)).isEqualTo(new Value.Dbl(29.7));
        assertThat(eval("field: study.none", instance)).isSameAs(NULL);
        assertThat(eval("field: study.missing", instance)).isSameAs(NULL);
        assertThat(eval("field: study.title.deeper", instance)).isSameAs(NULL);
        assertThat(eval("field: nothing", instance)).isSameAs(NULL);
        assertThat(eval("field: params.study_id", instance, Map.of("study_id", "LCS-99999"))).isEqualTo(text("LCS-99999"));
        assertThat(eval("field: params.other", instance, Map.of("study_id", "LCS-99999"))).isSameAs(NULL);
        // a scope variable comes before an instance key of the same name
        assertThat(eval("map: {over: {literal: [{title: X}]}, as: study, value: {field: study.title}}", instance))
                .isEqualTo(list(text("X")));
    }

    @Test
    void ref() {
        LinkedHashMap<String, Expr> let = new LinkedHashMap<>();
        let.put("title", expr("field: study.title"));
        let.put("loud", expr("format: {template: '{0}!', args: [{ref: title}]}"));
        Value v = new Evaluator(let, VOCABULARY, Evaluator.FunctionRegistry.NONE)
                .eval(expr("ref: loud"), Scope.root(json("{\"study\": {\"title\": \"T\"}}"), Map.of(), notes));
        assertThat(v).isEqualTo(text("T!"));
    }

    @Test
    void fieldReadsIntoALetValue() {
        LinkedHashMap<String, Expr> let = new LinkedHashMap<>();
        let.put("dataset", expr("at: {list: {field: datasets}, index: {field: params.dataset_index}}"));
        let.put("study", expr("literal: {title: shadowed}"));
        Evaluator e = new Evaluator(let, VOCABULARY, Evaluator.FunctionRegistry.NONE);
        Scope root = Scope.root(json("{\"datasets\": [{\"t\": \"D1\"}, {\"t\": \"D2\"}], \"study\": {\"title\": \"T\"}}"),
                Map.of("dataset_index", "2"), notes);
        assertThat(e.eval(expr("field: dataset.t"), root)).isEqualTo(text("D2"));
        // a let name comes before an instance key, and a variable before a let name
        assertThat(e.eval(expr("field: study.title"), root)).isEqualTo(text("shadowed"));
        assertThat(e.eval(expr("map: {over: {literal: [{t: v}]}, as: dataset, value: {field: dataset.t}}"), root))
                .isEqualTo(list(text("v")));
    }

    @Test
    void refCycleIsAnError() {
        LinkedHashMap<String, Expr> let = new LinkedHashMap<>();
        let.put("a", expr("ref: b"));
        let.put("b", expr("ref: a"));
        Evaluator e = new Evaluator(let, VOCABULARY, Evaluator.FunctionRegistry.NONE);
        assertThatThrownBy(() -> e.eval(expr("ref: a"), Scope.root(json("{}"), Map.of(), notes)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("refers to itself");
    }

    @Test
    void pos() {
        assertThat(eval("map: {over: {literal: [a, b, c]}, as: x, value: {pos: x}}"))
                .isEqualTo(list(new Value.Int(1), new Value.Int(2), new Value.Int(3)));
    }

    @Test
    void format() {
        assertThat(eval("format: {template: '{0}-{1:02}', args: [{literal: LCS-99999}, {literal: 1}]}")).isEqualTo(text("LCS-99999-01"));
        assertThat(eval("format: {template: '{0}: {1}', args: [{literal: a}, {literal: 29.7}]}")).isEqualTo(text("a: 29.7"));
        // a text that is an integer takes a width, as the dataset index parameter does
        assertThat(eval("format: {template: '{0:02}', args: [{field: params.i}]}", "{}", Map.of("i", "3"))).isEqualTo(text("03"));
        assertThat(eval("format: {template: '{{{0:3}}}', args: [{literal: -7}]}")).isEqualTo(text("{ -7}"));
        assertThat(eval("format: {template: '{0:03}|{1}', args: [{literal: -1}, {literal: null}]}")).isEqualTo(text("-01|"));
        assertThat(eval("map: {over: {literal: [1, 2]}, as: x, value: {format: {template: 'assay[{0}]', args: [{pos: x}]}}}"))
                .isEqualTo(list(text("assay[1]"), text("assay[2]")));
    }

    @Test
    void join() {
        assertThat(eval("join: {items: [{literal: a}, {literal: null}, {literal: b}], sep: '|'}")).isEqualTo(text("a|b"));
        assertThat(eval("join: {items: [{literal: ''}, {literal: 1}, {literal: [x, '', y]}], sep: '; '}")).isEqualTo(text("1; x; y"));
        assertThat(eval("join: {items: {literal: [p, q]}, sep: ' / '}")).isEqualTo(text("p / q"));
        assertThat(eval("join: {items: [{user-param: {name: {literal: column}, value: {literal: 'C18, 2 mm'}}},"
                + " {cvparam: {cv: {literal: MS}, accession: {literal: 'MS:1'}, name: {literal: n}}}], sep: '|'}"))
                .isEqualTo(text("[, , column, \"C18, 2 mm\"]|[MS, MS:1, n, ]"));
        assertThatThrownBy(() -> eval("join: {items: [{ref-to: {element: sample, name: {literal: P1}}}], sep: '|'}"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reference");
    }

    @Test
    void first() {
        assertThat(eval("first: [{literal: null}, {literal: ''}, {literal: x}]")).isEqualTo(text("x"));
        assertThat(eval("first: [{literal: null}, {literal: []}]")).isSameAs(NULL);
        assertThat(eval("first: [{literal: 0}, {literal: x}]")).isEqualTo(new Value.Int(0));
    }

    @Test
    void split() {
        assertThat(eval("split: {value: {literal: ' a; b ;; c ; '}, sep: ';'}")).isEqualTo(list(text("a"), text("b"), text("c")));
        assertThat(eval("split: {value: {literal: null}, sep: ';'}")).isEqualTo(list());
    }

    @Test
    void when() {
        assertThat(eval("when: {if: {literal: x}, then: {literal: 'yes'}, else: {literal: 'no'}}")).isEqualTo(text("yes"));
        assertThat(eval("when: {if: {literal: ''}, then: {literal: 'yes'}, else: {literal: 'no'}}")).isEqualTo(text("no"));
        assertThat(eval("when: {if: {literal: []}, then: {literal: 'yes'}}")).isSameAs(NULL);
        assertThat(eval("when: {if: {literal: 0}, then: {literal: 'yes'}}")).isEqualTo(text("yes"));
    }

    @Test
    void eq() {
        assertThat(eval("eq: [{literal: 'yes'}, {literal: 'yes'}]")).isEqualTo(Value.TRUE);
        assertThat(eval("eq: [{literal: 'Yes'}, {literal: 'yes'}]")).isSameAs(NULL);
        assertThat(eval("eq: [{literal: 1}, {literal: '1'}]")).isEqualTo(Value.TRUE);
        assertThat(eval("eq: [{literal: null}, {literal: ''}]")).isEqualTo(Value.TRUE);
    }

    @Test
    void lower() {
        assertThat(eval("lower: {literal: UnPublished}")).isEqualTo(text("unpublished"));
        assertThat(eval("lower: {literal: null}")).isSameAs(NULL);
    }

    @Test
    void notEmpty() {
        assertThat(eval("not-empty: {literal: a}")).isEqualTo(Value.TRUE);
        assertThat(eval("not-empty: {literal: 0}")).isEqualTo(Value.TRUE);
        assertThat(eval("not-empty: {literal: ''}")).isSameAs(NULL);
        assertThat(eval("not-empty: {literal: null}")).isSameAs(NULL);
        assertThat(eval("not-empty: {literal: []}")).isSameAs(NULL);
        assertThat(eval("not-empty: {literal: {}}")).isSameAs(NULL);
    }

    @Test
    void count() {
        assertThat(eval("count: {literal: [1, 2, 3]}")).isEqualTo(new Value.Int(3));
        assertThat(eval("count: {literal: null}")).isEqualTo(new Value.Int(0));
    }

    @Test
    void at() {
        assertThat(eval("at: {list: {literal: [a, b]}, index: {literal: 1}}")).isEqualTo(text("a"));
        assertThat(eval("at: {list: {literal: [a]}, index: {literal: 2}}")).isSameAs(NULL);
        assertThat(eval("at: {list: {literal: [a]}, index: {literal: 0}}")).isSameAs(NULL);
    }

    @Test
    void concat() {
        assertThat(eval("concat: [{literal: [a]}, {literal: [b, c]}, {literal: null}]")).isEqualTo(list(text("a"), text("b"), text("c")));
    }

    @Test
    void entries() {
        assertThat(eval("entries: {field: s.factors}", "{\"s\": {\"factors\": {\"BMI\": 29.7}}}"))
                .isEqualTo(list(obj("key", text("BMI"), "value", new Value.Dbl(29.7))));
        assertThat(eval("entries: {literal: null}")).isEqualTo(list());
    }

    private static final String SAMPLES = "{\"samples\": [{\"n\": \"P1\", \"g\": \"A\"}, {\"n\": \"P2\", \"g\": \"B\"},"
            + " {\"n\": \"P3\", \"g\": \"A\"}]}";

    @Test
    void map() {
        assertThat(eval("map: {over: {field: samples}, as: s, value: {field: s.n}}", SAMPLES))
                .isEqualTo(list(text("P1"), text("P2"), text("P3")));
        assertThat(eval("map: {over: {literal: null}, as: s, value: {field: s.n}}")).isEqualTo(list());
    }

    @Test
    void find() {
        assertThat(eval("find: {in: {field: samples}, as: s, where: {eq: [{field: s.g}, {literal: B}]}}", SAMPLES))
                .isEqualTo(obj("n", text("P2"), "g", text("B")));
        assertThat(eval("find: {in: {field: samples}, as: s, where: {eq: [{field: s.g}, {literal: C}]}}", SAMPLES)).isSameAs(NULL);
        assertThat(eval("find: {in: {field: samples}, as: s, where: {eq: [{pos: s}, {literal: 3}]}}", SAMPLES))
                .isEqualTo(obj("n", text("P3"), "g", text("A")));
    }

    @Test
    void filter() {
        assertThat(eval("map: {over: {filter: {in: {field: samples}, as: s, where: {eq: [{field: s.g}, {literal: A}]}}}, as: t, value: {field: t.n}}",
                SAMPLES)).isEqualTo(list(text("P1"), text("P3")));
        assertThat(eval("filter: {in: {field: samples}, as: s, where: {literal: null}}", SAMPLES)).isEqualTo(list());
    }

    @Test
    void term() {
        assertThat(eval("term: {value: {literal: {text: 'mol% (mole percent of the measured lipidome)'}}}"))
                .isEqualTo(new Value.ParamV(new Param("UO", "UO:0000076", "mole fraction", "mol%")));
        assertThat(eval("term: {value: {literal: {text: Lipidome study}}}"))
                .isEqualTo(new Value.ParamV(new Param("", "", "Lipidome study", "")));
        assertThat(eval("term: {value: {literal: {cv: NCBITaxon, accession: 'NCBITaxon:9606', name: Homo sapiens}}}"))
                .isEqualTo(new Value.ParamV(new Param("NCBITaxon", "NCBITaxon:9606", "Homo sapiens", "")));
        assertThat(eval("term: {value: {literal: {cv: MS, accession: 'MS:1003966', name: LipidSearch}}, param-value: {literal: 4.2}}"))
                .isEqualTo(new Value.ParamV(new Param("MS", "MS:1003966", "LipidSearch", "4.2")));
        assertThat(eval("term: {value: {literal: free text}, param-value: {literal: 3}}"))
                .isEqualTo(new Value.ParamV(new Param("", "", "free text", "3")));
        assertThat(eval("term: {value: {literal: null}}")).isSameAs(NULL);
        assertThat(eval("term: {value: {literal: ''}}")).isSameAs(NULL);
        assertThat(eval("term: {value: {literal: ' '}}")).isSameAs(NULL);
    }

    @Test
    void cvparam() {
        assertThat(eval("cvparam: {cv: {literal: NCIT}, accession: {literal: 'NCIT:C28421'}, name: {literal: Sex}, value: {literal: female}}"))
                .isEqualTo(new Value.ParamV(new Param("NCIT", "NCIT:C28421", "Sex", "female")));
        assertThat(eval("cvparam: {cv: {literal: MS}, accession: {literal: 'MS:1002962'}, name: {literal: mean}}"))
                .isEqualTo(new Value.ParamV(new Param("MS", "MS:1002962", "mean", "")));
        assertThat(eval("cvparam: {cv: {literal: MS}, accession: {literal: ''}, name: {literal: ''}}")).isSameAs(NULL);
    }

    @Test
    void userParam() {
        assertThat(eval("user-param: {name: {literal: sample preparation}, value: {literal: Folch}}"))
                .isEqualTo(new Value.ParamV(new Param("", "", "sample preparation", "Folch")));
        assertThat(eval("user-param: {name: {literal: goslin normalized lipid shorthand name}}"))
                .isEqualTo(new Value.ParamV(new Param("", "", "goslin normalized lipid shorthand name", "")));
        assertThat(eval("user-param: {name: {literal: ''}, value: {literal: v}}")).isSameAs(NULL);
    }

    @Test
    void label() {
        assertThat(eval("label: {literal: {cv: DOID, accession: 'DOID:9352', name: type 2 diabetes mellitus}}")).isEqualTo(text("type 2 diabetes mellitus"));
        assertThat(eval("label: {literal: {text: Control}}")).isEqualTo(text("Control"));
        assertThat(eval("label: {literal: 45}")).isEqualTo(text("45"));
        assertThat(eval("label: {literal: 29.70}")).isEqualTo(text("29.7"));
        assertThat(eval("label: {literal: abc}")).isEqualTo(text("abc"));
        assertThat(eval("label: {literal: null}")).isEqualTo(text("null"));
        assertThat(eval("label: {literal: {name: null}}")).isSameAs(NULL);
    }

    @Test
    void number() {
        assertThat(eval("number: {literal: 1.0e-5}")).isEqualTo(text("1e-05"));
        assertThat(eval("number: {literal: 45}")).isEqualTo(text("45"));
        assertThat(eval("number: {literal: null}")).isEqualTo(text("null"));
    }

    @Test
    void normalise() {
        assertThat(eval("normalise: {kind: doi, value: {literal: 'https://doi.org/10.1234/abc.5'}}")).isEqualTo(text("10.1234/abc.5"));
        assertThat(eval("normalise: {kind: email, value: {literal: jane.doe@uni-x.de}}")).isEqualTo(text("jane.doe@uni-x.de"));
        assertThat(notes.all()).isEmpty();
        assertThat(eval("normalise: {kind: doi, value: {literal: xxxx}}")).isSameAs(NULL);
        assertThat(eval("normalise: {kind: email, value: {literal: jane@}}")).isSameAs(NULL);
        assertThat(eval("normalise: {kind: email, value: {literal: null}}")).isSameAs(NULL);
        assertThat(notes.all()).containsExactly("'xxxx' is not a DOI", "'jane@' is not an e-mail address");
    }

    @Test
    void match() {
        assertThat(eval("match: {value: {literal: 'LCS-00123'}, regex: 'LCS-(?<n>\\d{5})', group: n}")).isEqualTo(text("00123"));
        assertThat(eval("match: {value: {literal: 'LCS-DRAFT'}, regex: 'LCS-(?<n>\\d{5})', group: n}")).isSameAs(NULL);
        assertThat(eval("match: {value: {literal: 'ab'}, regex: '(?<x>a)|(?<y>b)', group: y}")).isSameAs(NULL);
    }

    @Test
    void convertUnit() {
        assertThat(eval("convert-unit: {value: {literal: 2.5}, from: {literal: nmol/mL}, to: {literal: µmol/L}}")).isEqualTo(new Value.Dbl(2.5));
        assertThat(eval("convert-unit: {value: {literal: 7}, from: {literal: pmol/mL}, to: {literal: nmol/L}}")).isEqualTo(new Value.Int(7));
        assertThat(eval("convert-unit: {value: {literal: 7}, from: {literal: µmol/L}, to: {literal: µmol/L}}")).isEqualTo(new Value.Int(7));
        assertThat(eval("convert-unit: {value: {literal: null}, from: {literal: nmol/mL}, to: {literal: µmol/L}}")).isSameAs(NULL);
        assertThat(eval("convert-unit: {value: {literal: 1}, from: {literal: g}, to: {literal: kg}}")).isSameAs(NULL);
        assertThat(notes.all()).containsExactly("no factor converts g to kg");
    }

    @Test
    void aggregate() {
        assertThat(eval("aggregate: {fn: mean, values: {literal: [2, 2]}}")).isEqualTo(new Value.Dbl(2.0));
        assertThat(eval("aggregate: {fn: mean, values: {literal: [1.0, null, 3.0]}}")).isEqualTo(new Value.Dbl(2.0));
        assertThat(eval("aggregate: {fn: cv, values: {literal: [1.0, 3.0]}}")).isEqualTo(new Value.Dbl(1.4142135623730951 / 2.0));
        assertThat(eval("aggregate: {fn: cv, values: {literal: [1.0]}}")).isSameAs(NULL);
        assertThat(eval("aggregate: {fn: cv, values: {literal: [-1.0, 1.0]}}")).isSameAs(NULL);
        assertThat(eval("aggregate: {fn: mean, values: {literal: [null, null]}}")).isSameAs(NULL);
        assertThat(eval("aggregate: {fn: cv, values: {literal: [null, null]}}")).isSameAs(NULL);
    }

    @Test
    void refTo() {
        assertThat(eval("ref-to: {element: sample, name: {literal: P001}}")).isEqualTo(new Value.Ref("sample", text("P001")));
    }

    @Test
    void fn() {
        Evaluator.FunctionRegistry registry = (name, args, n) -> {
            n.add("called " + name);
            return new Value.Int(args.size());
        };
        Value v = new Evaluator(Map.of(), VOCABULARY, registry)
                .eval(expr("fn: {name: goslin-parse, args: [{literal: PC 34:1}]}"), Scope.root(json("{}"), Map.of(), notes));
        assertThat(v).isEqualTo(new Value.Int(1));
        assertThat(notes.all()).containsExactly("called goslin-parse");
        assertThatThrownBy(() -> eval("fn: {name: goslin-parse, args: [{literal: PC 34:1}]}"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("goslin-parse");
    }

    @Test
    void metadataNumbersEachItemThatProducedALine() {
        Mapping m = MappingLoader.read("mapping-test/each.yaml");
        Evaluator e = new Evaluator(m.let(), VOCABULARY, Evaluator.FunctionRegistry.NONE);
        String instance = "{\"samples\": [{\"name\": \"P1\", \"species\": null, \"extras\": [\"\", \"x\"]},"
                + " {\"name\": \"\", \"extras\": []}, {\"name\": \"P3\", \"extras\": [\"y\", \"z\"]}]}";
        List<Evaluator.MetadataLine> lines = e.metadata(m.metadata(), Scope.root(json(instance), Map.of("id", "S"), notes));
        assertThat(lines).extracting(l -> l.key() + "=" + Numbers.python(l.value())).containsExactly(
                "mzTab-ID=S",
                "sample[1]=P1", "sample[1]-custom[1]=x",
                "sample[2]=P3", "sample[2]-custom[1]=y", "sample[2]-custom[2]=z");
    }

    @Test
    void tableDropsAColumnWhoseWhereIsFalse() {
        Mapping m = MappingLoader.read("mapping-test/table.yaml");
        Evaluator e = new Evaluator(m.let(), VOCABULARY, Evaluator.FunctionRegistry.NONE);
        String instance = "{\"lipids\": [{\"name\": \"PC 34:1\", \"v\": [1, 2.5]}, {\"name\": \"SM 34:1;O2\", \"v\": [null, 3]}],"
                + " \"assays\": [\"a1\", \"a2\"]}";
        Evaluator.Table t = e.table(m.tables().get(0), Scope.root(json(instance), Map.of(), notes));
        assertThat(t.section()).isEqualTo("SML");
        assertThat(t.header()).isEqualTo("SMH");
        assertThat(t.columns()).containsExactly("SML_ID", "chemical_name", "abundance_assay[1]", "abundance_assay[2]");
        assertThat(t.rows()).containsExactly(
                List.of(new Value.Int(1), text("PC 34:1"), text("1"), text("2.5")),
                List.of(new Value.Int(2), text("SM 34:1;O2"), text("null"), text("3")));
    }
}
