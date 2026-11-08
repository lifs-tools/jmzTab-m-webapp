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

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.PathType;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/**
 * Reads mapping files: YAML, validated against {@code schema/mapping.schema.json}, then checked for the rules the
 * schema cannot express (variables, {@code let} names, regular expressions and templates).
 */
public final class MappingLoader {

    private static final String ROOT = "submission-vocabulary/";
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9.-]*");
    private static final Pattern KEY_PLACEHOLDER = Pattern.compile("\\{([^{}]*)\\}");
    private static final Pattern FORMAT_PLACEHOLDER = Pattern.compile("\\{\\{|\\}\\}|\\{(\\d+)(?::(0?)(\\d+))?\\}|[{}]");
    private static final YAMLMapper YAML = YAMLMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    /** Guarded by the class: see {@link #schema()}. */
    private static JsonSchema schema;

    private MappingLoader() {
    }

    /**
     * The mapping {@code id} from {@code submission-vocabulary/mappings/{input|output}/<id>.yaml}.
     *
     * @throws MappingRequestException when {@code id} is not an id or there is no mapping {@code id}
     * @throws InvalidMappingException when the file is not a valid mapping
     */
    public static Mapping load(String id) {
        if (!ID.matcher(id).matches()) {
            throw new MappingRequestException("Not a mapping id: " + id);
        }
        List<Mapping.Kind> found = new ArrayList<>();
        for (Mapping.Kind kind : Mapping.Kind.values()) {
            if (MappingLoader.class.getClassLoader().getResource(path(kind, id)) != null) {
                found.add(kind);
            }
        }
        if (found.isEmpty()) {
            throw new MappingRequestException("Unknown mapping: " + id);
        }
        if (found.size() > 1) {
            throw new InvalidMappingException("mapping " + id + " is both an input and an output mapping");
        }
        Mapping.Kind kind = found.get(0);
        Mapping m = read(path(kind, id));
        if (!m.id().equals(id)) {
            throw new InvalidMappingException(path(kind, id) + ": /id is " + m.id() + ", not the file name " + id);
        }
        if (m.kind() != kind) {
            throw new InvalidMappingException(path(kind, id) + ": /kind is " + m.kind().name().toLowerCase(Locale.ROOT)
                    + ", not the folder " + kind.name().toLowerCase(Locale.ROOT));
        }
        return m;
    }

    private static String path(Mapping.Kind kind, String id) {
        return ROOT + "mappings/" + kind.name().toLowerCase(Locale.ROOT) + "/" + id + ".yaml";
    }

    /** The mapping in the classpath resource {@code resourcePath}. */
    public static Mapping read(String resourcePath) {
        URL url = MappingLoader.class.getClassLoader().getResource(resourcePath);
        if (url == null) {
            throw new IllegalArgumentException("No such resource: " + resourcePath);
        }
        try (InputStream in = url.openStream()) {
            return read(in, resourcePath);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The mapping in {@code in}; {@code source} names it in error messages. */
    public static Mapping read(InputStream in, String source) {
        JsonNode node;
        try {
            node = YAML.readTree(in);
        } catch (JacksonException e) {
            throw new InvalidMappingException(source + ": not valid YAML: " + e.getOriginalMessage(), e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (node == null || node.isMissingNode()) {
            throw new InvalidMappingException(source + ": empty");
        }
        Set<ValidationMessage> errors = schema().validate(node);
        if (!errors.isEmpty()) {
            // a message starts with the JSON pointer of the node, which is empty for the top level
            throw new InvalidMappingException(source + ": " + errors.stream().map(ValidationMessage::getMessage)
                    .map(m -> m.startsWith(": ") ? "(top level)" + m : m).collect(Collectors.joining("; ")));
        }
        try {
            return new Parser(node.has("let") ? iterable(node.get("let").fieldNames()) : List.of()).mapping(node);
        } catch (InvalidMappingException e) {
            throw new InvalidMappingException(source + ": " + e.getMessage());
        }
    }

    /** One expression, without schema validation or {@code let} name checks; for tests. */
    static Expr expression(JsonNode node) {
        return new Parser(null).expr(node, "");
    }

    /** The mapping schema, read once on first use; a failed read is tried again on the next use. */
    private static synchronized JsonSchema schema() {
        if (schema == null) {
            try (InputStream in = MappingLoader.class.getClassLoader().getResourceAsStream(ROOT + "schema/mapping.schema.json")) {
                if (in == null) {
                    throw new IllegalStateException("No mapping.schema.json in the submission vocabulary");
                }
                SchemaValidatorsConfig config = SchemaValidatorsConfig.builder().pathType(PathType.JSON_POINTER)
                        .locale(Locale.ENGLISH).build();
                schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(YAML.readTree(in), config);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return schema;
    }

    private static List<String> iterable(java.util.Iterator<String> it) {
        List<String> out = new ArrayList<>();
        it.forEachRemaining(out::add);
        return out;
    }

    /** Turns a schema-valid tree into records, checking the rules the schema cannot express. */
    private static final class Parser {

        /** The let names, or null when they are not checked. */
        private final Set<String> letNames;
        /** The variables in scope, innermost first. */
        private final Deque<String> variables = new ArrayDeque<>();
        /** The variables whose {@code {x#}} a key or column name may use, innermost first. */
        private final Deque<String> numbered = new ArrayDeque<>();

        Parser(List<String> letNames) {
            this.letNames = letNames == null ? null : Set.copyOf(letNames);
        }

        Mapping mapping(JsonNode n) {
            List<String> parameters = new ArrayList<>();
            if (n.has("parameters")) {
                n.get("parameters").forEach(p -> parameters.add(p.asText()));
            }
            LinkedHashMap<String, Mapping.CvInfo> cvs = new LinkedHashMap<>();
            if (n.has("cvs")) {
                n.get("cvs").properties().forEach(e -> cvs.put(e.getKey(), new Mapping.CvInfo(
                        e.getValue().get("full-name").asText(), e.getValue().get("version").asText(), e.getValue().get("uri").asText())));
            }
            LinkedHashMap<String, Expr> let = new LinkedHashMap<>();
            if (n.has("let")) {
                n.get("let").properties().forEach(e -> let.put(e.getKey(), expr(e.getValue(), "/let/" + escape(e.getKey()))));
            }
            List<Mapping.MetadataRule> metadata = n.has("metadata") ? metadataRules(n.get("metadata"), "/metadata") : List.of();
            List<Mapping.TableRule> tables = new ArrayList<>();
            if (n.has("tables")) {
                for (int i = 0; i < n.get("tables").size(); i++) {
                    tables.add(table(n.get("tables").get(i), "/tables/" + i));
                }
            }
            List<Mapping.Sheet> sheets = new ArrayList<>();
            if (n.has("sheets")) {
                n.get("sheets").forEach(s -> sheets.add(sheet(s)));
            }
            return new Mapping(n.get("id").asText(), n.get("version").asText(),
                    Mapping.Kind.valueOf(n.get("kind").asText().toUpperCase(Locale.ROOT)), n.get("vocabulary").asText(),
                    n.has("target") ? n.get("target").asText() : null, parameters, cvs, let, metadata, tables, sheets);
        }

        private static Mapping.Sheet sheet(JsonNode s) {
            LinkedHashMap<String, String> columns = new LinkedHashMap<>();
            if (s.has("columns")) {
                s.get("columns").properties().forEach(c -> columns.put(c.getKey(), c.getValue().asText()));
            }
            return new Mapping.Sheet(text(s, "sheet"), text(s, "named-by"), s.get("group").asText(), s.get("layout").asText(),
                    columns, text(s, "factor-columns"));
        }

        private static String text(JsonNode n, String key) {
            return n.has(key) ? n.get(key).asText() : null;
        }

        private List<Mapping.MetadataRule> metadataRules(JsonNode rules, String at) {
            List<Mapping.MetadataRule> out = new ArrayList<>();
            for (int i = 0; i < rules.size(); i++) {
                JsonNode r = rules.get(i);
                String p = at + "/" + i;
                if (r.has("each")) {
                    Expr over = expr(r.get("each"), p + "/each");
                    String as = r.get("as").asText();
                    variables.push(as);
                    numbered.push(as);
                    Expr where = r.has("where") ? expr(r.get("where"), p + "/where") : null;
                    List<Mapping.MetadataRule> lines = metadataRules(r.get("lines"), p + "/lines");
                    numbered.pop();
                    variables.pop();
                    out.add(new Mapping.Each(over, as, where, lines));
                } else {
                    String key = r.get("key").asText();
                    checkPlaceholders(key, p + "/key");
                    out.add(new Mapping.Line(key, expr(r.get("value"), p + "/value")));
                }
            }
            return out;
        }

        private Mapping.TableRule table(JsonNode t, String at) {
            Expr rows = expr(t.get("rows"), at + "/rows");
            String as = t.get("as").asText();
            List<Mapping.ColumnRule> columns = new ArrayList<>();
            JsonNode cs = t.get("columns");
            for (int i = 0; i < cs.size(); i++) {
                JsonNode c = cs.get(i);
                String p = at + "/columns/" + i;
                if (c.has("each")) {
                    Expr over = expr(c.get("each"), p + "/each");
                    String var = c.get("as").asText();
                    variables.push(var);
                    numbered.push(var);
                    List<Mapping.Column> inner = new ArrayList<>();
                    for (int j = 0; j < c.get("columns").size(); j++) {
                        inner.add(column(c.get("columns").get(j), as, p + "/columns/" + j));
                    }
                    numbered.pop();
                    variables.pop();
                    columns.add(new Mapping.EachColumn(over, var, inner));
                } else {
                    columns.add(column(c, as, p));
                }
            }
            return new Mapping.TableRule(t.get("section").asText(), t.get("header").asText(), rows, as, columns);
        }

        /** A column: its where sees the table's scope, its value also the row variable {@code row}. */
        private Mapping.Column column(JsonNode c, String row, String at) {
            String header = c.get("header").asText();
            checkPlaceholders(header, at + "/header");
            Expr where = c.has("where") ? expr(c.get("where"), at + "/where") : null;
            variables.push(row);
            Expr value = expr(c.get("value"), at + "/value");
            variables.pop();
            return new Mapping.Column(header, value, where);
        }

        private void checkPlaceholders(String template, String at) {
            Matcher m = KEY_PLACEHOLDER.matcher(template);
            while (m.find()) {
                String inner = m.group(1);
                if (!inner.endsWith("#") || !numbered.contains(inner.substring(0, inner.length() - 1))) {
                    throw new InvalidMappingException(at + ": {" + inner + "} is not {x#} for the variable x of an enclosing each");
                }
            }
        }

        Expr expr(JsonNode n, String at) {
            if (!n.isObject() || n.size() != 1) {
                throw new InvalidMappingException(at + ": an expression is an object with exactly one key");
            }
            String form = n.fieldNames().next();
            JsonNode a = n.get(form);
            String p = at + "/" + form;
            return switch (form) {
                case "literal" -> {
                    if (hasBoolean(a)) {
                        throw new InvalidMappingException(p + ": a literal holds no booleans; quote yes, no, true and false to mean the text");
                    }
                    yield new Expr.Literal(Value.of(a));
                }
                case "field" -> new Expr.Field(List.of(a.asText().split("\\.", -1)));
                case "ref" -> {
                    if (letNames != null && !letNames.contains(a.asText())) {
                        throw new InvalidMappingException(p + ": no let named " + a.asText());
                    }
                    yield new Expr.Ref(a.asText());
                }
                case "pos" -> {
                    if (!variables.contains(a.asText())) {
                        throw new InvalidMappingException(p + ": no variable " + a.asText() + " in scope");
                    }
                    yield new Expr.Pos(a.asText());
                }
                case "format" -> format(a, p);
                case "join" -> new Expr.Join(a.get("items").isArray() ? exprs(a.get("items"), p + "/items") : List.of(expr(a.get("items"), p + "/items")),
                        a.get("sep").asText());
                case "first" -> new Expr.First(exprs(a, p));
                case "split" -> new Expr.Split(expr(a.get("value"), p + "/value"), a.get("sep").asText());
                case "when" -> new Expr.When(expr(a.get("if"), p + "/if"), expr(a.get("then"), p + "/then"), optional(a, "else", p));
                case "eq" -> new Expr.Eq(expr(a.get(0), p + "/0"), expr(a.get(1), p + "/1"));
                case "lower" -> new Expr.Lower(expr(a, p));
                case "not-empty" -> new Expr.NotEmpty(expr(a, p));
                case "count" -> new Expr.Count(expr(a, p));
                case "at" -> new Expr.At(expr(a.get("list"), p + "/list"), expr(a.get("index"), p + "/index"));
                case "concat" -> new Expr.Concat(exprs(a, p));
                case "entries" -> new Expr.Entries(expr(a, p));
                case "map" -> {
                    Expr over = expr(a.get("over"), p + "/over");
                    yield new Expr.Map(over, a.get("as").asText(), bound(a.get("as").asText(), a.get("value"), p + "/value"));
                }
                case "find" -> {
                    Expr in = expr(a.get("in"), p + "/in");
                    yield new Expr.Find(in, a.get("as").asText(), bound(a.get("as").asText(), a.get("where"), p + "/where"));
                }
                case "filter" -> {
                    Expr in = expr(a.get("in"), p + "/in");
                    yield new Expr.Filter(in, a.get("as").asText(), bound(a.get("as").asText(), a.get("where"), p + "/where"));
                }
                case "term" -> new Expr.Term(expr(a.get("value"), p + "/value"), optional(a, "param-value", p));
                case "cvparam" -> new Expr.CvParam(expr(a.get("cv"), p + "/cv"), expr(a.get("accession"), p + "/accession"),
                        expr(a.get("name"), p + "/name"), optional(a, "value", p));
                case "user-param" -> new Expr.UserParam(expr(a.get("name"), p + "/name"), optional(a, "value", p));
                case "label" -> new Expr.Label(expr(a, p));
                case "number" -> new Expr.Number(expr(a, p));
                case "normalise" -> new Expr.Normalise(Expr.Identifier.valueOf(a.get("kind").asText().toUpperCase(Locale.ROOT)),
                        expr(a.get("value"), p + "/value"));
                case "match" -> match(a, p);
                case "convert-unit" -> new Expr.ConvertUnit(expr(a.get("value"), p + "/value"), expr(a.get("from"), p + "/from"),
                        expr(a.get("to"), p + "/to"));
                case "aggregate" -> new Expr.Aggregate(Expr.AggregateFunction.valueOf(a.get("fn").asText().toUpperCase(Locale.ROOT)),
                        expr(a.get("values"), p + "/values"));
                case "ref-to" -> new Expr.RefTo(a.get("element").asText(), expr(a.get("name"), p + "/name"));
                case "fn" -> new Expr.Fn(a.get("name").asText(), a.has("args") ? exprs(a.get("args"), p + "/args") : List.of());
                default -> throw new InvalidMappingException(at + ": unknown expression " + form);
            };
        }

        private List<Expr> exprs(JsonNode array, String at) {
            List<Expr> out = new ArrayList<>(array.size());
            for (int i = 0; i < array.size(); i++) {
                out.add(expr(array.get(i), at + "/" + i));
            }
            return out;
        }

        private Expr optional(JsonNode a, String key, String at) {
            return a.has(key) ? expr(a.get(key), at + "/" + key) : null;
        }

        private Expr bound(String variable, JsonNode n, String at) {
            variables.push(variable);
            try {
                return expr(n, at);
            } finally {
                variables.pop();
            }
        }

        private Expr format(JsonNode a, String p) {
            List<Expr> args = a.has("args") ? exprs(a.get("args"), p + "/args") : List.of();
            String template = a.get("template").asText();
            List<Expr.Format.Part> parts = new ArrayList<>();
            StringBuilder chunk = new StringBuilder();
            Matcher m = FORMAT_PLACEHOLDER.matcher(template);
            int last = 0;
            while (m.find()) {
                chunk.append(template, last, m.start());
                last = m.end();
                String token = m.group();
                if (token.equals("{{") || token.equals("}}")) {
                    chunk.append(token.charAt(0));
                } else if (m.group(1) != null) {
                    int index = Integer.parseInt(m.group(1));
                    if (index >= args.size()) {
                        throw new InvalidMappingException(p + "/template: {" + index + "} has no argument");
                    }
                    if (!chunk.isEmpty()) {
                        parts.add(new Expr.Format.Chunk(chunk.toString()));
                        chunk.setLength(0);
                    }
                    parts.add(new Expr.Format.Placeholder(index, m.group(3) == null ? 0 : Integer.parseInt(m.group(3)),
                            "0".equals(m.group(2))));
                } else {
                    throw new InvalidMappingException(p + "/template: a single " + token + " at " + m.start()
                            + "; placeholders are {n} or {n:0w}, and {{ and }} are braces");
                }
            }
            chunk.append(template, last, template.length());
            if (!chunk.isEmpty()) {
                parts.add(new Expr.Format.Chunk(chunk.toString()));
            }
            return new Expr.Format(parts, args);
        }

        private Expr match(JsonNode a, String p) {
            Pattern regex;
            try {
                regex = Pattern.compile(a.get("regex").asText());
            } catch (PatternSyntaxException e) {
                throw new InvalidMappingException(p + "/regex: " + e.getDescription());
            }
            String group = a.get("group").asText();
            if (!regex.namedGroups().containsKey(group)) {
                throw new InvalidMappingException(p + "/group: the regex has no group named " + group);
            }
            return new Expr.Match(expr(a.get("value"), p + "/value"), regex, group);
        }

        private static boolean hasBoolean(JsonNode n) {
            if (n.isBoolean()) {
                return true;
            }
            for (JsonNode child : n) {
                if (hasBoolean(child)) {
                    return true;
                }
            }
            return false;
        }

        private static String escape(String key) {
            return key.replace("~", "~0").replace("/", "~1");
        }
    }
}
