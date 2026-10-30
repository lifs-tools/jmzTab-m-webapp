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
package org.lifstools.mztab2.server.config;

import io.swagger.v3.core.util.Yaml31;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.springdoc.core.customizers.OpenApiCustomizer;

/**
 * Publishes the mzTab-M models of both version lines in the webapp's OpenAPI
 * document. The webapp has no jmzTab-m: its {@code /rest/v2/validate} and
 * {@code /convert} bodies and the {@code /convertPlain} result are whatever
 * model the engine of the file's line reads and writes. The OpenAPI
 * descriptions of the models are copies of {@code mzTab_m_openapi.yml} of each
 * line's jmzTab-m ({@value #LOCATION}{@code <line>.yml}; the engines'
 * {@code WebappCopiesTest} keeps them equal).
 *
 * <p>Every schema of a line is added as {@code <Name>_<suffix>}, e.g.
 * {@code MzTab_2_0} and {@code StudyVariable_2_1}, with every reference
 * {@code #/components/schemas/X} inside it rewritten to {@code X_<suffix>}.
 * {@code ValidationMessage}, {@code ValidationLocation} and {@code Error} are
 * left out: the webapp publishes its own messages and errors. The bodies are
 * then {@code oneOf} the {@code MzTab} schemas of the lines.
 */
public class MzTabSchemasCustomizer implements OpenApiCustomizer {

    static final String LOCATION = "/mztab-schemas/";
    static final String REF_PREFIX = "#/components/schemas/";
    static final Set<String> LEFT_OUT = Set.of("ValidationMessage", "ValidationLocation", "Error");
    /** The lines whose models are published, in the order of the {@code oneOf}s. */
    static final List<String> LINES = List.of("2.0", "2.1");

    private final Map<String, Map<String, Schema>> schemasBySuffix;

    public MzTabSchemasCustomizer() {
        Map<String, Map<String, Schema>> bySuffix = new LinkedHashMap<>();
        for (String line : LINES) {
            bySuffix.put(suffix(line), renamed(read(line), suffix(line)));
        }
        this.schemasBySuffix = bySuffix;
    }

    @Override
    public void customise(OpenAPI openApi) {
        if (openApi.getComponents() == null) {
            openApi.setComponents(new Components());
        }
        schemasBySuffix.values().forEach(schemas -> schemas.forEach(openApi.getComponents()::addSchemas));
        List<String> refs = LINES.stream().map(line -> REF_PREFIX + "MzTab" + suffix(line)).toList();
        requestBody(openApi, "/rest/v2/validate", refs);
        requestBody(openApi, "/rest/v2/convert", refs);
        okResponse(openApi, "/rest/v2/convertPlain", refs);
    }

    /** {@code _2_0} for {@code 2.0}. */
    static String suffix(String line) {
        return "_" + line.replace('.', '_');
    }

    /** The components of one line's OpenAPI description. */
    @SuppressWarnings("rawtypes")
    private static Map<String, Schema> read(String line) {
        String resource = LOCATION + line + ".yml";
        try (InputStream in = MzTabSchemasCustomizer.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(resource + " is missing from the classpath");
            }
            OpenAPI description = Yaml31.mapper().readValue(in, OpenAPI.class);
            if (description.getComponents() == null || description.getComponents().getSchemas() == null) {
                throw new IllegalStateException(resource + " has no schemas");
            }
            return description.getComponents().getSchemas();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + resource, e);
        }
    }

    @SuppressWarnings("rawtypes")
    static Map<String, Schema> renamed(Map<String, Schema> schemas, String suffix) {
        Function<String, String> rename = ref -> ref.startsWith(REF_PREFIX) ? ref + suffix : ref;
        Map<String, Schema> out = new LinkedHashMap<>();
        schemas.forEach((name, schema) -> {
            if (!LEFT_OUT.contains(name)) {
                rewriteRefs(schema, rename);
                out.put(name + suffix, schema);
            }
        });
        return out;
    }

    /** Rewrites the references of a schema and of every schema inside it. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    static void rewriteRefs(Schema schema, Function<String, String> rename) {
        if (schema == null) {
            return;
        }
        if (schema.get$ref() != null) {
            schema.set$ref(rename.apply(schema.get$ref()));
        }
        if (schema.getProperties() != null) {
            ((Map<String, Schema>) schema.getProperties()).values().forEach(s -> rewriteRefs(s, rename));
        }
        if (schema.getPatternProperties() != null) {
            ((Map<String, Schema>) schema.getPatternProperties()).values().forEach(s -> rewriteRefs(s, rename));
        }
        rewriteRefs(schema.getItems(), rename);
        rewriteRefs(schema.getNot(), rename);
        rewriteAll(schema.getAllOf(), rename);
        rewriteAll(schema.getOneOf(), rename);
        rewriteAll(schema.getAnyOf(), rename);
        rewriteAll(schema.getPrefixItems(), rename);
        if (schema.getAdditionalProperties() instanceof Schema additional) {
            rewriteRefs(additional, rename);
        }
    }

    @SuppressWarnings("rawtypes")
    private static void rewriteAll(List<Schema> schemas, Function<String, String> rename) {
        if (schemas != null) {
            schemas.forEach(s -> rewriteRefs(s, rename));
        }
    }

    @SuppressWarnings("rawtypes")
    private static Schema oneOf(List<String> refs) {
        List<Schema> alternatives = new ArrayList<>();
        refs.forEach(ref -> alternatives.add(new JsonSchema().$ref(ref)));
        return new JsonSchema().oneOf(alternatives);
    }

    private static void requestBody(OpenAPI openApi, String path, List<String> refs) {
        Operation post = post(openApi, path);
        if (post.getRequestBody() == null) {
            throw new IllegalStateException(path + " has no request body to describe");
        }
        json(post.getRequestBody().getContent(), path).setSchema(oneOf(refs));
    }

    private static void okResponse(OpenAPI openApi, String path, List<String> refs) {
        Operation post = post(openApi, path);
        ApiResponse ok = post.getResponses() == null ? null : post.getResponses().get("200");
        if (ok == null) {
            throw new IllegalStateException(path + " has no 200 response to describe");
        }
        json(ok.getContent(), path).setSchema(oneOf(refs));
    }

    private static Operation post(OpenAPI openApi, String path) {
        PathItem item = openApi.getPaths() == null ? null : openApi.getPaths().get(path);
        if (item == null || item.getPost() == null) {
            throw new IllegalStateException("The OpenAPI document has no POST " + path);
        }
        return item.getPost();
    }

    private static MediaType json(Content content, String path) {
        MediaType json = content == null ? null : content.get(org.springframework.http.MediaType.APPLICATION_JSON_VALUE);
        if (json == null) {
            throw new IllegalStateException(path + " has no application/json content to describe");
        }
        return json;
    }
}
