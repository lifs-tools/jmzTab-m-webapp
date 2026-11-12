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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.lifstools.mztab.submission.mapping.Notes;
import org.lifstools.mztab.submission.mapping.Value;

class FactorCombinationsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Value NOT_SPECIFIED = obj("text", text("not specified"));

    private static Value json(String json) {
        try {
            return Value.of(JSON.readTree(json.replace('\'', '"')));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Value text(String s) {
        return new Value.Text(s);
    }

    private static Value obj(Object... keysAndValues) {
        LinkedHashMap<String, Value> m = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            m.put((String) keysAndValues[i], (Value) keysAndValues[i + 1]);
        }
        return new Value.Obj(m);
    }

    private static Value list(Value... items) {
        return new Value.ListV(List.of(items));
    }

    private static Value members(long... positions) {
        return new Value.ListV(java.util.Arrays.stream(positions).<Value>mapToObj(Value.Int::new).toList());
    }

    private static Value group(Value key, Value members) {
        return obj("key", key, "members", members);
    }

    private static Value factor(String name, Value value) {
        return obj("factor", text(name), "value", value);
    }

    private static Value combinations(Value names, Value samples, Value factors) {
        return new Functions().call("factor-combinations", List.of(names, samples, factors), new Notes());
    }

    private static final Value SAMPLES = json("""
            [{'sample_name': 'S1', 'disease': {'text': 'T2DM'}, 'sex': null, 'age': 40, 'factors': {'BMI': 29.7}},
             {'sample_name': 'S2', 'disease': {'text': 'T2DM'}, 'sex': null, 'age': 0, 'factors': {'bmi': 22.3}},
             {'sample_name': 'S3', 'disease': {'text': 'Control'}, 'sex': null, 'age': 51, 'factors': {}}]""");

    private static final Value NAMES = list(text("S1"), text("S2"), text("S3"));

    @Test
    void withoutAPrimaryFactorAllAssaysAreOneGroup() {
        Value factors = json("[{'factor_name': 'disease', 'primary': {'text': 'no'}}, {'factor_name': 'sex', 'primary': null}]");
        assertThat(combinations(NAMES, SAMPLES, factors)).isEqualTo(list(group(list(), members(1, 2, 3))));
        assertThat(combinations(NAMES, SAMPLES, list())).isEqualTo(list(group(list(), members(1, 2, 3))));
    }

    @Test
    void groupsByThePrimaryFactorsValuesInOrderOfFirstAppearance() {
        Value factors = json("[{'factor_name': 'Disease', 'primary': {'text': 'yes'}}]");
        assertThat(combinations(NAMES, SAMPLES, factors)).isEqualTo(list(
                group(list(factor("Disease", obj("text", text("T2DM")))), members(1, 2)),
                group(list(factor("Disease", obj("text", text("Control")))), members(3))));
    }

    @Test
    void membersAreAssayPositionsInTheOrderOfTheNames() {
        Value factors = json("[{'factor_name': 'disease', 'primary': {'text': 'yes'}}]");
        Value names = list(text("S3"), text("S1"), text("S2"));
        assertThat(combinations(names, SAMPLES, factors)).isEqualTo(list(
                group(list(factor("disease", obj("text", text("Control")))), members(1)),
                group(list(factor("disease", obj("text", text("T2DM")))), members(2, 3))));
    }

    @Test
    void otherFactorsAreReadFromTheSampleFactorsIgnoringCase() {
        Value factors = json("[{'factor_name': 'disease', 'primary': {'text': 'yes'}}, {'factor_name': 'Bmi', 'primary': {'text': 'yes'}}]");
        assertThat(combinations(NAMES, SAMPLES, factors)).isEqualTo(list(
                group(list(factor("disease", obj("text", text("T2DM"))), factor("Bmi", new Value.Dbl(29.7))), members(1)),
                group(list(factor("disease", obj("text", text("T2DM"))), factor("Bmi", new Value.Dbl(22.3))), members(2)),
                group(list(factor("disease", obj("text", text("Control"))), factor("Bmi", NOT_SPECIFIED)), members(3))));
    }

    @Test
    void aMissingOrFalsyValueIsNotSpecified() {
        // Python's `or`: None, "", 0 and {} all give the default
        Value factors = json("[{'factor_name': 'sex', 'primary': {'text': 'yes'}}, {'factor_name': 'age', 'primary': {'text': 'yes'}}]");
        assertThat(combinations(NAMES, SAMPLES, factors)).isEqualTo(list(
                group(list(factor("sex", NOT_SPECIFIED), factor("age", new Value.Int(40))), members(1)),
                group(list(factor("sex", NOT_SPECIFIED), factor("age", NOT_SPECIFIED)), members(2)),
                group(list(factor("sex", NOT_SPECIFIED), factor("age", new Value.Int(51))), members(3))));
    }

    @Test
    void samplesMayBeGivenByName() {
        Value byName = obj("S1", ((Value.ListV) SAMPLES).value().get(0), "S2", ((Value.ListV) SAMPLES).value().get(1),
                "S3", ((Value.ListV) SAMPLES).value().get(2));
        Value factors = json("[{'factor_name': 'disease', 'primary': {'text': 'yes'}}]");
        assertThat(combinations(NAMES, byName, factors)).isEqualTo(combinations(NAMES, SAMPLES, factors));
    }

    @Test
    void anUnknownSampleIsAnError() {
        assertThatThrownBy(() -> combinations(list(text("S9")), SAMPLES, list()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("S9");
    }

    @Test
    void givesTheStudyGroupsOfTheV3Example() {
        JsonNode instance = V3Example.instance();
        Value samples = Value.of(instance.get("samples"));
        Value factors = Value.of(instance.get("factors"));
        Value names = Value.of(instance.get("datasets").get(0).get("quantities").get("samples"));
        Value t2dm = json("{'cv': 'NCIT', 'accession': 'NCIT:C26747', 'name': 'Type 2 Diabetes Mellitus'}");
        Value control = json("{'cv': 'NCIT', 'accession': 'NCIT:C28143', 'name': 'Control Group'}");
        Value groups = combinations(names, samples, factors);
        assertThat(groups).isEqualTo(list(
                group(list(factor("disease", t2dm)), members(1, 2, 3, 4)),
                group(list(factor("disease", control)), members(5, 6, 7, 8))));
    }
}
