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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * The submission vocabulary: the field dictionary and the value lists of the
 * LipidCompass submission template, as published in the
 * {@code submission-vocabulary/} resources of this module.
 */
public final class Vocabulary {

    private static final String ROOT = "submission-vocabulary/";
    private static final YAMLMapper YAML = new YAMLMapper();

    /** A field of a group. Optional attributes are null when absent; {@code required} is the v3 value rendered as text. */
    public record Field(String id, String label, String required, String list, String type, String versionOf,
            String mztab, List<String> ontologies, String help, String example) {
    }

    /** A group of fields; {@code help} is null when the group has none. */
    public record Group(String id, boolean workbook, List<Field> fields, String help) {
    }

    /** A parameter as written in mzTab-M: cv, accession, name and value. */
    public record Param(String cv, String accession, String name, String value) {
    }

    /** An entry of a value list: a CV term or a fixed text. */
    public sealed interface Entry {

        /** A CV term. */
        record Term(String cv, String accession, String label) implements Entry {
        }

        /** A text choice; {@code note} and {@code encoding} are null when absent. */
        record Text(String text, String note, Param encoding) implements Entry {
        }
    }

    /** A named value list. */
    public record ValueList(String name, List<Entry> entries) {
    }

    private final String version;
    private final Map<String, Group> groups;
    private final Map<String, ValueList> lists = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile Map<String, Param> encodings;

    private Vocabulary(String version, Map<String, Group> groups) {
        this.version = version;
        this.groups = groups;
    }

    /** Loads the vocabulary from the classpath. */
    public static Vocabulary load() {
        JsonNode root = read("vocabulary/vocabulary.yaml");
        Map<String, Group> groups = new LinkedHashMap<>();
        root.get("groups").properties().forEach(g -> {
            List<Field> fields = new ArrayList<>();
            g.getValue().get("fields").forEach(f -> fields.add(field(f)));
            groups.put(g.getKey(), new Group(g.getKey(), g.getValue().get("workbook").asBoolean(),
                    List.copyOf(fields), text(g.getValue(), "help")));
        });
        return new Vocabulary(root.get("vocabulary").get("version").asText(), Collections.unmodifiableMap(groups));
    }

    private static Field field(JsonNode f) {
        List<String> ontologies = null;
        if (f.has("ontologies")) {
            List<String> o = new ArrayList<>();
            f.get("ontologies").forEach(n -> o.add(n.asText()));
            ontologies = List.copyOf(o);
        }
        return new Field(f.get("id").asText(), f.get("label").asText(), text(f, "required"), text(f, "list"),
                text(f, "type"), text(f, "version_of"), text(f, "mztab"), ontologies, text(f, "help"), text(f, "example"));
    }

    private static String text(JsonNode n, String key) {
        return n.has(key) ? n.get(key).asText() : null;
    }

    private static JsonNode read(String path) {
        try (InputStream in = Vocabulary.class.getClassLoader().getResourceAsStream(ROOT + path)) {
            if (in == null) {
                throw new IllegalArgumentException("Not in the submission vocabulary: " + path);
            }
            return YAML.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The vocabulary version, e.g. 1.0.0. */
    public String version() {
        return version;
    }

    /** The group with the given id, e.g. {@code study}. */
    public Group group(String id) {
        Group g = groups.get(id);
        if (g == null) {
            throw new IllegalArgumentException("Unknown group: " + id);
        }
        return g;
    }

    /** The value list with the given name. */
    public ValueList list(String name) {
        if (!name.matches("[a-z][a-z0-9_]*")) {
            throw new IllegalArgumentException("Not a list name: " + name);
        }
        return lists.computeIfAbsent(name, n -> {
            JsonNode root = read("lists/" + n + ".yaml");
            List<Entry> entries = new ArrayList<>();
            root.get("entries").forEach(e -> {
                if (e.has("accession")) {
                    entries.add(new Entry.Term(e.get("cv").asText(), e.get("accession").asText(), e.get("label").asText()));
                } else {
                    Param encoding = null;
                    if (e.has("encoding")) {
                        JsonNode p = e.get("encoding");
                        encoding = new Param(p.get("cv").asText(), p.get("accession").asText(), p.get("name").asText(), p.get("value").asText());
                    }
                    entries.add(new Entry.Text(e.get("text").asText(), text(e, "note"), encoding));
                }
            });
            return new ValueList(n, List.copyOf(entries));
        });
    }

    /** The names of all value lists, sorted. */
    public List<String> listNames() {
        try {
            URL url = Vocabulary.class.getClassLoader().getResource(ROOT + "lists");
            if (url == null) {
                throw new IllegalStateException("No lists in the submission vocabulary");
            }
            URI uri = url.toURI();
            if ("jar".equals(uri.getScheme())) {
                try (FileSystem fs = FileSystems.newFileSystem(uri, Map.of())) {
                    return names(fs.getPath("/" + ROOT + "lists"));
                }
            }
            return names(Path.of(uri));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> names(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".yaml"))
                    .map(n -> n.substring(0, n.length() - ".yaml".length())).sorted().toList();
        }
    }

    /** The agreed encoding of a text entry in any list, if there is one. */
    public Optional<Param> encodingOf(String text) {
        Map<String, Param> m = encodings;
        if (m == null) {
            m = new TreeMap<>();
            for (String name : listNames()) {
                for (Entry e : list(name).entries()) {
                    if (e instanceof Entry.Text t && t.encoding() != null) {
                        m.put(t.text(), t.encoding());
                    }
                }
            }
            encodings = m;
        }
        return Optional.ofNullable(m.get(text));
    }
}
