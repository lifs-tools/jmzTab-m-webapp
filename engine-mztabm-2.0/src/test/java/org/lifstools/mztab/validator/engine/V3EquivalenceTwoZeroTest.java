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
package org.lifstools.mztab.validator.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.lifstools.mztab.validator.engine.api.MappingApplyRequest;
import org.lifstools.mztab.validator.engine.api.MappingApplyResult;
import org.lifstools.mztab.validator.engine.api.ValidationLevel;
import org.lifstools.mztab.validator.engine.api.ValidationMessage;
import org.lifstools.mztab.validator.engine.api.ValidationMessage.MessageType;
import org.lifstools.mztab.validator.engine.mapping.MappingApplier;
import org.lifstools.mztab2.cvmapping.CvParameterLookupService;

/**
 * The v3 equivalence (design §8): the v3 example's vocabulary instance, through the output mapping
 * {@code mztab-2.0.0-M} in this engine, equals the checked-in output of the bb8dcbf converter
 * ({@code submission-vocabulary/examples/v3-example}), apart from the metadata lines its
 * {@code allowed-differences.yaml} lists, and validates without errors or warnings.
 */
class V3EquivalenceTwoZeroTest {

    private static final String EXAMPLE = "/submission-vocabulary/examples/v3-example/";
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;

    private final EngineValidator validator = new EngineValidator(mock(CvParameterLookupService.class), JSON);

    @ParameterizedTest
    @ValueSource(strings = {"01", "02"})
    void v3ExampleMatchesTheConverter(String n) {
        MappingApplyResult result = apply(n);

        assertThat(normalised(result.text())).isEqualTo(normalised(text("expected/LCS-99999-" + n + ".mzTab")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"01", "02"})
    void outputHasNoErrorsOrWarnings(String n) throws IOException {
        Path file = dir.resolve("LCS-99999-" + n + ".mzTab");
        Files.writeString(file, apply(n).text(), StandardCharsets.UTF_8);

        List<ValidationMessage> messages = validator.validate(file, ValidationLevel.INFO, 100, null).messages();

        List<String> text = messages.stream().map(m -> m.getMessageType() + " " + m.getCode() + ": " + m.getMessage())
            .toList();
        assertThat(messages).as("messages: %s", text)
            .noneMatch(m -> m.getMessageType() == MessageType.ERROR || m.getMessageType() == MessageType.WARN);
    }

    /** The output mapping applied to the example, as a new file, with the provenance of the v3 route. */
    private static MappingApplyResult apply(String n) {
        JsonNode parameters = json("parameters.json").get(Integer.parseInt(n) - 1);
        Map<String, String> p = new LinkedHashMap<>();
        parameters.properties().forEach(e -> p.put(e.getKey(), e.getValue().asText()));
        MappingApplyRequest request = new MappingApplyRequest("mztab-2.0.0-M", "1.0.0", p, json("instance.json"), "",
            true, "v3-workbook 1.0.0");
        return new MappingApplier().apply(request, engineVersions());
    }

    /**
     * The text without the metadata lines whose key starts with an allowed key prefix, its metadata lines sorted by
     * key, and every other line in its place after them.
     */
    private static List<String> normalised(String text) {
        List<String> prefixes = allowedKeyPrefixes();
        List<String> metadata = new ArrayList<>();
        List<String> rest = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            if (line.startsWith("MTD\t")) {
                String key = line.split("\t", -1)[1];
                if (prefixes.stream().noneMatch(key::startsWith)) {
                    metadata.add(line);
                }
            } else {
                rest.add(line);
            }
        }
        metadata.sort(Comparator.comparing(line -> line.split("\t", -1)[1]));
        List<String> out = new ArrayList<>(metadata);
        out.addAll(rest);
        return out;
    }

    private static List<String> allowedKeyPrefixes() {
        try {
            JsonNode differences = new YAMLMapper().readTree(text("allowed-differences.yaml"));
            List<String> prefixes = new ArrayList<>();
            differences.forEach(d -> prefixes.add(d.get("key-prefix").asText()));
            return prefixes;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The mzTab-M versions this engine serves, from its filtered {@code application.properties}. */
    private static Set<String> engineVersions() {
        Properties properties = new Properties();
        try (InputStream in = V3EquivalenceTwoZeroTest.class.getResourceAsStream("/application.properties")) {
            properties.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Arrays.stream(properties.getProperty("engine.mztab-versions").split(",")).map(String::strip)
            .collect(Collectors.toUnmodifiableSet());
    }

    private static JsonNode json(String name) {
        try {
            return JSON.readTree(text(name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String text(String name) {
        try (InputStream in = V3EquivalenceTwoZeroTest.class.getResourceAsStream(EXAMPLE + name)) {
            if (in == null) {
                throw new IllegalStateException(EXAMPLE + name + " is missing from the test classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
