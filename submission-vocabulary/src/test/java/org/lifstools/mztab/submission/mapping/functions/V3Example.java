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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The v3 example: the reader's instance and the converter's output (lipidcompass-submissions bb8dcbf). */
final class V3Example {

    private static final String ROOT = "/submission-vocabulary/examples/v3-example/";

    private V3Example() {
    }

    /** The vocabulary instance the v3 reader gives for the example workbook. */
    static JsonNode instance() {
        try (InputStream in = V3Example.class.getResourceAsStream(ROOT + "instance.json")) {
            return new ObjectMapper().readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The SML rows of the converter's output file {@code name}, each as column name to cell. */
    static List<Map<String, String>> smallMolecules(String name) {
        String text;
        try (InputStream in = V3Example.class.getResourceAsStream(ROOT + "expected/" + name)) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<String> header = null;
        List<Map<String, String>> rows = new ArrayList<>();
        for (String line : text.split("\n")) {
            List<String> cells = Arrays.asList(line.split("\t", -1));
            if (cells.get(0).equals("SMH")) {
                header = cells;
            } else if (cells.get(0).equals("SML")) {
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 0; i < header.size(); i++) {
                    row.put(header.get(i), cells.get(i));
                }
                rows.add(row);
            }
        }
        return rows;
    }
}
