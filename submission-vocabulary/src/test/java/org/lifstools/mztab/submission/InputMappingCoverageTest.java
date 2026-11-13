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

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.lifstools.mztab.submission.mapping.Mapping;
import org.lifstools.mztab.submission.mapping.MappingLoader;

/** The v3-workbook input mapping reads every field the workbook renders, each from exactly one column. */
class InputMappingCoverageTest {

    @Test
    void everyWorkbookFieldAppearsExactlyOnce() {
        Vocabulary vocabulary = Vocabulary.load();
        Mapping v3 = MappingLoader.load("v3-workbook");
        assertThat(v3.kind()).isEqualTo(Mapping.Kind.INPUT);
        assertThat(v3.version()).isEqualTo("1.2.0");
        assertThat(v3.vocabulary()).isEqualTo(vocabulary.version());

        List<String> workbookGroups = vocabulary.groups().stream().filter(Vocabulary.Group::workbook)
                .map(Vocabulary.Group::id).toList();
        assertThat(v3.sheets()).extracting(Mapping.Sheet::group).containsExactlyInAnyOrderElementsOf(workbookGroups);
        for (Vocabulary.Group group : vocabulary.groups()) {
            if (!group.workbook()) {
                continue;
            }
            List<String> read = v3.sheets().stream().filter(s -> s.group().equals(group.id()))
                    .flatMap(s -> s.columns().values().stream()).toList();
            assertThat(read).as(group.id())
                    .containsExactlyInAnyOrderElementsOf(group.fields().stream().map(Vocabulary.Field::id).toList());
        }
    }

    @Test
    void layoutsAreConventionsAndSheetNamesAreFields() throws IOException {
        Vocabulary vocabulary = Vocabulary.load();
        List<String> conventions = new ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream("/submission-vocabulary/vocabulary/vocabulary.yaml")) {
            new YAMLMapper().readTree(in).get("conventions").fieldNames().forEachRemaining(conventions::add);
        }
        for (Mapping.Sheet sheet : MappingLoader.load("v3-workbook").sheets()) {
            assertThat(conventions).as("layout of " + sheet).contains(sheet.layout());
            if (sheet.namedBy() != null) {
                String[] field = sheet.namedBy().split("\\.");
                assertThat(vocabulary.group(field[0]).fields()).as("named-by of " + sheet)
                        .extracting(Vocabulary.Field::id).contains(field[1]);
            }
        }
    }
}
