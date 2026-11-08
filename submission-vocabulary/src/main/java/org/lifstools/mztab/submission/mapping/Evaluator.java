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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import org.lifstools.mztab.submission.Vocabulary;
import org.lifstools.mztab.submission.Vocabulary.Param;

/**
 * Evaluates the expressions and rules of a mapping, as {@code schema/mapping-language.md} defines them. Errors in
 * the use of a form (a list where a text is needed, an unknown function) throw {@link IllegalArgumentException}.
 */
public final class Evaluator {

    /** The named functions behind the {@code fn} form. */
    @FunctionalInterface
    public interface FunctionRegistry {

        /** No functions: every call throws. */
        FunctionRegistry NONE = (name, args, notes) -> {
            throw new IllegalArgumentException("No implementation of the function " + name);
        };

        /** Calls the function {@code name}; it may add notes. */
        Value call(String name, List<Value> args, Notes notes);
    }

    /** A metadata line before rendering: its key with every placeholder filled, and its value, never empty. */
    public record MetadataLine(String key, Value value) {
    }

    /** A table before rendering: the column names of the kept columns, and one list of cell values per row. */
    public record Table(String section, String header, List<String> columns, List<List<Value>> rows) {

        public Table {
            columns = List.copyOf(columns);
            rows = rows.stream().map(List::copyOf).toList();
        }
    }

    private static final String UNIT_FACTORS = "submission-vocabulary/lists/unit-factors.yaml";
    private static volatile Map<String, Value> unitFactors;

    private final Map<String, Expr> let;
    private final Function<String, Optional<Param>> encodings;
    private final FunctionRegistry functions;

    /**
     * @param let the mapping's named values; each is evaluated once per root scope, in that scope, and a field path
     *     may start with its name
     * @param vocabulary the vocabulary whose agreed encodings {@code term} applies
     * @param functions the named functions
     */
    public Evaluator(Map<String, Expr> let, Vocabulary vocabulary, FunctionRegistry functions) {
        this(let, vocabulary::encodingOf, functions);
    }

    /** As the public constructor, with the agreed encodings given directly; for tests. */
    Evaluator(Map<String, Expr> let, Function<String, Optional<Param>> encodings, FunctionRegistry functions) {
        this.let = Map.copyOf(let);
        this.encodings = Objects.requireNonNull(encodings);
        this.functions = Objects.requireNonNull(functions);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Rules

    /**
     * The metadata lines of {@code rules}, in rule order: lines whose value is empty are left out, and a {@code {x#}}
     * in a key is the position of {@code x} among the items of its {@code each} that gave at least one line.
     */
    public List<MetadataLine> metadata(List<Mapping.MetadataRule> rules, Scope scope) {
        List<MetadataLine> out = new ArrayList<>();
        for (Mapping.MetadataRule rule : rules) {
            switch (rule) {
                case Mapping.Line line -> {
                    Value v = eval(line.value(), scope);
                    if (!Value.isEmpty(v)) {
                        out.add(new MetadataLine(line.key(), v));
                    }
                }
                case Mapping.Each each -> {
                    List<Value> items = list(eval(each.over(), scope), "each");
                    int numbered = 0;
                    for (int i = 0; i < items.size(); i++) {
                        Scope inner = scope.with(each.as(), items.get(i), i + 1);
                        if (each.where() != null && Value.isEmpty(eval(each.where(), inner))) {
                            continue;
                        }
                        List<MetadataLine> lines = metadata(each.lines(), inner);
                        if (!lines.isEmpty()) {
                            String placeholder = "{" + each.as() + "#}";
                            String n = Integer.toString(++numbered);
                            for (MetadataLine l : lines) {
                                out.add(new MetadataLine(l.key().replace(placeholder, n), l.value()));
                            }
                        }
                    }
                }
            }
        }
        return out;
    }

    /**
     * The table of {@code rule}: a column whose {@code where} does not hold (evaluated once, without the row
     * variable) is left out; a {@code {x#}} in a column name is the position of {@code x} among the items of its
     * {@code each} that kept at least one column.
     */
    public Table table(Mapping.TableRule rule, Scope scope) {
        record Kept(String name, Expr value, Scope scope) {
        }
        List<Kept> kept = new ArrayList<>();
        for (Mapping.ColumnRule c : rule.columns()) {
            switch (c) {
                case Mapping.Column column -> {
                    if (column.where() == null || !Value.isEmpty(eval(column.where(), scope))) {
                        kept.add(new Kept(column.header(), column.value(), scope));
                    }
                }
                case Mapping.EachColumn each -> {
                    List<Value> items = list(eval(each.over(), scope), "each");
                    int numbered = 0;
                    for (int i = 0; i < items.size(); i++) {
                        Scope inner = scope.with(each.as(), items.get(i), i + 1);
                        List<Mapping.Column> columns = new ArrayList<>();
                        for (Mapping.Column column : each.columns()) {
                            if (column.where() == null || !Value.isEmpty(eval(column.where(), inner))) {
                                columns.add(column);
                            }
                        }
                        if (!columns.isEmpty()) {
                            String n = Integer.toString(++numbered);
                            for (Mapping.Column column : columns) {
                                kept.add(new Kept(column.header().replace("{" + each.as() + "#}", n), column.value(), inner));
                            }
                        }
                    }
                }
            }
        }
        List<Value> rows = list(eval(rule.rows(), scope), "rows");
        List<List<Value>> cells = new ArrayList<>(rows.size());
        for (int r = 0; r < rows.size(); r++) {
            List<Value> row = new ArrayList<>(kept.size());
            for (Kept k : kept) {
                row.add(eval(k.value(), k.scope().with(rule.as(), rows.get(r), r + 1)));
            }
            cells.add(row);
        }
        return new Table(rule.section(), rule.header(), kept.stream().map(Kept::name).toList(), cells);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Expressions

    /** The value of {@code e} in {@code s}. */
    public Value eval(Expr e, Scope s) {
        return switch (e) {
            case Expr.Literal l -> l.value();
            case Expr.Field f -> field(f, s);
            case Expr.Ref r -> let(r.name(), s);
            case Expr.Pos p -> new Value.Int(bound(p.variable(), s).position());
            case Expr.Format f -> format(f, s);
            case Expr.Join j -> join(j, s);
            case Expr.First f -> first(f, s);
            case Expr.Split sp -> split(sp, s);
            case Expr.When w -> !Value.isEmpty(eval(w.condition(), s)) ? eval(w.then(), s)
                    : w.otherwise() == null ? Value.Null.INSTANCE : eval(w.otherwise(), s);
            case Expr.Eq q -> test(text(eval(q.left(), s), "eq").equals(text(eval(q.right(), s), "eq")));
            case Expr.Lower l -> lower(eval(l.value(), s));
            case Expr.NotEmpty n -> test(!Value.isEmpty(eval(n.value(), s)));
            case Expr.Count c -> new Value.Int(list(eval(c.list(), s), "count").size());
            case Expr.At a -> at(a, s);
            case Expr.Concat c -> concat(c, s);
            case Expr.Entries en -> entries(eval(en.object(), s));
            case Expr.Map m -> map(m, s);
            case Expr.Find f -> find(f, s);
            case Expr.Filter f -> filter(f, s);
            case Expr.Term t -> term(t, s);
            case Expr.CvParam p -> param(text(eval(p.cv(), s), "cvparam"), text(eval(p.accession(), s), "cvparam"),
                    text(eval(p.name(), s), "cvparam"), optionalText(p.value(), s, "cvparam"));
            case Expr.UserParam p -> param("", "", text(eval(p.name(), s), "user-param"), optionalText(p.value(), s, "user-param"));
            case Expr.Label l -> label(eval(l.value(), s));
            case Expr.Number n -> number(eval(n.value(), s));
            case Expr.Normalise n -> normalise(n, s);
            case Expr.Match m -> match(m, s);
            case Expr.ConvertUnit c -> convertUnit(c, s);
            case Expr.Aggregate a -> aggregate(a, s);
            case Expr.RefTo r -> new Value.Ref(r.element(), eval(r.name(), s));
            case Expr.Fn f -> functions.call(f.name(), f.args().stream().map(a -> eval(a, s)).toList(), s.notes());
        };
    }

    /** The head is a bound variable, a let name, {@code params}, or a top-level instance key, in that order. */
    private Value field(Expr.Field f, Scope s) {
        List<String> path = f.path();
        String head = path.get(0);
        Scope.Binding b = s.binding(head);
        Value v = b != null ? b.value() : let.containsKey(head) ? let(head, s)
                : "params".equals(head) ? s.parameters() : s.instanceMember(head);
        for (int i = 1; i < path.size(); i++) {
            v = v instanceof Value.Obj o ? o.get(path.get(i)) : Value.Null.INSTANCE;
        }
        return v;
    }

    private Value let(String name, Scope s) {
        Map<String, Value> values = s.letValues();
        Value v = values.get(name);
        if (v != null) {
            return v;
        }
        Expr e = let.get(name);
        if (e == null) {
            throw new IllegalArgumentException("No let named " + name);
        }
        if (!s.evaluating().add(name)) {
            throw new IllegalArgumentException("The let " + name + " refers to itself");
        }
        try {
            v = eval(e, s.top());
        } finally {
            s.evaluating().remove(name);
        }
        values.put(name, v);
        return v;
    }

    private static Scope.Binding bound(String variable, Scope s) {
        Scope.Binding b = s.binding(variable);
        if (b == null) {
            throw new IllegalArgumentException("No variable " + variable + " in scope");
        }
        return b;
    }

    private Value format(Expr.Format f, Scope s) {
        List<Value> args = f.args().stream().map(a -> eval(a, s)).toList();
        StringBuilder out = new StringBuilder();
        for (Expr.Format.Part part : f.parts()) {
            switch (part) {
                case Expr.Format.Chunk c -> out.append(c.text());
                case Expr.Format.Placeholder p -> {
                    Value v = args.get(p.index());
                    if (p.width() == 0) {
                        out.append(text(v, "format"));
                    } else {
                        out.append(pad(integer(v, "format"), p.width(), p.zeros()));
                    }
                }
            }
        }
        return new Value.Text(out.toString());
    }

    /** Python {@code format(n, "0W")} or {@code format(n, "W")}. */
    private static String pad(long n, int width, boolean zeros) {
        String digits = Long.toString(Math.abs(n));
        String sign = n < 0 ? "-" : "";
        int fill = width - sign.length() - digits.length();
        if (fill <= 0) {
            return sign + digits;
        }
        return zeros ? sign + "0".repeat(fill) + digits : " ".repeat(fill) + sign + digits;
    }

    private Value join(Expr.Join j, Scope s) {
        List<String> parts = new ArrayList<>();
        for (Expr item : j.items()) {
            Value v = eval(item, s);
            for (Value x : v instanceof Value.ListV l ? l.value() : List.of(v)) {
                if (x instanceof Value.Ref) {
                    throw new IllegalArgumentException("join cannot render a reference; give the list of references as the value instead");
                }
                String t = x instanceof Value.ParamV p ? Render.param(p.value()) : text(x, "join");
                if (t != null && !t.isEmpty()) {
                    parts.add(t);
                }
            }
        }
        return new Value.Text(String.join(j.sep(), parts));
    }

    private Value first(Expr.First f, Scope s) {
        for (Expr item : f.items()) {
            Value v = eval(item, s);
            if (!Value.isEmpty(v)) {
                return v;
            }
        }
        return Value.Null.INSTANCE;
    }

    private Value split(Expr.Split sp, Scope s) {
        String t = text(eval(sp.value(), s), "split");
        List<Value> parts = new ArrayList<>();
        int from = 0;
        while (true) {
            int at = t.indexOf(sp.sep(), from);
            String part = Render.strip(at < 0 ? t.substring(from) : t.substring(from, at));
            if (!part.isEmpty()) {
                parts.add(new Value.Text(part));
            }
            if (at < 0) {
                break;
            }
            from = at + sp.sep().length();
        }
        return new Value.ListV(parts);
    }

    private static Value lower(Value v) {
        return v == Value.Null.INSTANCE ? v : new Value.Text(text(v, "lower").toLowerCase(Locale.ROOT));
    }

    private Value at(Expr.At a, Scope s) {
        List<Value> items = list(eval(a.list(), s), "at");
        long i = integer(eval(a.index(), s), "at");
        return i >= 1 && i <= items.size() ? items.get((int) i - 1) : Value.Null.INSTANCE;
    }

    private Value concat(Expr.Concat c, Scope s) {
        List<Value> out = new ArrayList<>();
        for (Expr e : c.lists()) {
            out.addAll(list(eval(e, s), "concat"));
        }
        return new Value.ListV(out);
    }

    private static Value entries(Value v) {
        if (v == Value.Null.INSTANCE) {
            return new Value.ListV(List.of());
        }
        if (!(v instanceof Value.Obj o)) {
            throw new IllegalArgumentException("entries needs an object, not " + v);
        }
        List<Value> out = new ArrayList<>();
        o.value().forEach((k, x) -> {
            LinkedHashMap<String, Value> entry = new LinkedHashMap<>();
            entry.put("key", new Value.Text(k));
            entry.put("value", x);
            out.add(new Value.Obj(entry));
        });
        return new Value.ListV(out);
    }

    private Value map(Expr.Map m, Scope s) {
        List<Value> items = list(eval(m.over(), s), "map");
        List<Value> out = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            out.add(eval(m.value(), s.with(m.as(), items.get(i), i + 1)));
        }
        return new Value.ListV(out);
    }

    private Value find(Expr.Find f, Scope s) {
        List<Value> items = list(eval(f.in(), s), "find");
        for (int i = 0; i < items.size(); i++) {
            if (!Value.isEmpty(eval(f.where(), s.with(f.as(), items.get(i), i + 1)))) {
                return items.get(i);
            }
        }
        return Value.Null.INSTANCE;
    }

    private Value filter(Expr.Filter f, Scope s) {
        List<Value> items = list(eval(f.in(), s), "filter");
        List<Value> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            if (!Value.isEmpty(eval(f.where(), s.with(f.as(), items.get(i), i + 1)))) {
                out.add(items.get(i));
            }
        }
        return new Value.ListV(out);
    }

    /** {@code term} (conv:129-139). */
    private Value term(Expr.Term t, Scope s) {
        Value v = eval(t.value(), s);
        String value = optionalText(t.paramValue(), s, "term");
        if (v == Value.Null.INSTANCE || (v instanceof Value.Text x && x.value().isEmpty())) {
            return Value.Null.INSTANCE;
        }
        if (v instanceof Value.Obj o && !Value.isEmpty(o.get("accession"))) {
            return param(text(o.get("cv"), "term"), text(o.get("accession"), "term"), text(o.get("name"), "term"), value);
        }
        String label = v instanceof Value.Obj o ? text(o.get("text"), "term") : text(v, "term");
        Optional<Param> encoding = encodings.apply(label);
        if (encoding.isPresent()) {
            // param(*TEXT_TERMS[label]): None, too, when it would render as nothing
            Param p = encoding.get();
            return param(p.cv(), p.accession(), p.name(), p.value());
        }
        return param("", "", label, value);
    }

    /** A parameter, or Null when it would render as nothing (no accession and no name). */
    private static Value param(String cv, String accession, String name, String value) {
        Param p = new Param(cv, accession, name, value);
        return Render.param(p) == null ? Value.Null.INSTANCE : new Value.ParamV(p);
    }

    /** {@code label} (conv:141): {@code v.get("name") or v.get("text")} for an object, else {@link #number}. */
    private static Value label(Value v) {
        if (v instanceof Value.Obj o) {
            Value name = o.get("name");
            Value chosen = Value.isEmpty(name) ? o.get("text") : name;
            return chosen == Value.Null.INSTANCE ? chosen : new Value.Text(text(chosen, "label"));
        }
        return number(v);
    }

    /** {@code number} (conv:145). */
    private static Value number(Value v) {
        return new Value.Text(v == Value.Null.INSTANCE ? "null" : Numbers.python(v));
    }

    private Value normalise(Expr.Normalise n, Scope s) {
        Value v = eval(n.value(), s);
        if (Value.isEmpty(v)) {
            return Value.Null.INSTANCE;
        }
        String raw = text(v, "normalise");
        switch (n.kind()) {
            case DOI -> {
                String doi = Identifiers.doi(raw);
                if (doi != null) {
                    return new Value.Text(doi);
                }
                s.notes().add("'" + raw + "' is not a DOI");
            }
            case EMAIL -> {
                if (Identifiers.email(raw)) {
                    return new Value.Text(raw);
                }
                s.notes().add("'" + raw + "' is not an e-mail address");
            }
        }
        return Value.Null.INSTANCE;
    }

    private Value match(Expr.Match m, Scope s) {
        Value v = eval(m.value(), s);
        if (v == Value.Null.INSTANCE) {
            return v;
        }
        Matcher matcher = m.regex().matcher(text(v, "match"));
        if (!matcher.find()) {
            return Value.Null.INSTANCE;
        }
        String g = matcher.group(m.group());
        return g == null ? Value.Null.INSTANCE : new Value.Text(g);
    }

    private Value convertUnit(Expr.ConvertUnit c, Scope s) {
        Value v = eval(c.value(), s);
        if (v == Value.Null.INSTANCE) {
            return v;
        }
        String from = unit(eval(c.from(), s));
        String to = unit(eval(c.to(), s));
        if (from.equals(to)) {
            return checkNumber(v, "convert-unit");
        }
        Value factor = unitFactors().get(from + "\u0000" + to);
        if (factor == null) {
            s.notes().add("no factor converts " + from + " to " + to);
            return Value.Null.INSTANCE;
        }
        // Python arithmetic: int * int stays int, anything with a float is a float
        if (checkNumber(v, "convert-unit") instanceof Value.Int i && factor instanceof Value.Int f) {
            return new Value.Int(Math.multiplyExact(i.value(), f.value()));
        }
        return new Value.Dbl(asDouble(v) * asDouble(factor));
    }

    private static String unit(Value v) {
        Value l = label(v);
        return l == Value.Null.INSTANCE ? "" : ((Value.Text) l).value();
    }

    private Value aggregate(Expr.Aggregate a, Scope s) {
        List<Value> items = list(eval(a.values(), s), "aggregate");
        double[] values = items.stream().filter(v -> v != Value.Null.INSTANCE)
                .mapToDouble(v -> asDouble(checkNumber(v, "aggregate"))).toArray();
        if (values.length == 0) {
            return Value.Null.INSTANCE;
        }
        double mean = PyMath.fmean(values);
        return switch (a.function()) {
            case MEAN -> new Value.Dbl(mean);
            case CV -> values.length < 2 || mean == 0.0 ? Value.Null.INSTANCE : new Value.Dbl(PyMath.stdev(values) / mean);
        };
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Coercions

    private static Value test(boolean holds) {
        return holds ? Value.TRUE : Value.Null.INSTANCE;
    }

    /** A value as text, as Python's {@code str()} would give it; Null is the empty text. */
    private static String text(Value v, String form) {
        return switch (v) {
            case Value.Null n -> "";
            case Value.Text t -> t.value();
            case Value.Int i -> Long.toString(i.value());
            case Value.Dbl d -> Numbers.python(d.value());
            case Value.ParamV p -> Objects.requireNonNullElse(Render.param(p.value()), "");
            default -> throw new IllegalArgumentException(form + " needs a text or a number, not " + v);
        };
    }

    private String optionalText(Expr e, Scope s, String form) {
        return e == null ? "" : text(eval(e, s), form);
    }

    /** The items of a list; Null is the empty list. */
    private static List<Value> list(Value v, String form) {
        if (v == Value.Null.INSTANCE) {
            return List.of();
        }
        if (v instanceof Value.ListV l) {
            return l.value();
        }
        throw new IllegalArgumentException(form + " needs a list, not " + v);
    }

    /** An integer, or a text that is one (as parameters are). */
    private static long integer(Value v, String form) {
        if (v instanceof Value.Int i) {
            return i.value();
        }
        if (v instanceof Value.Text t) {
            try {
                return Long.parseLong(Render.strip(t.value()));
            } catch (NumberFormatException e) {
                // fall through
            }
        }
        throw new IllegalArgumentException(form + " needs an integer, not " + v);
    }

    private static Value checkNumber(Value v, String form) {
        if (v instanceof Value.Int || v instanceof Value.Dbl) {
            return v;
        }
        throw new IllegalArgumentException(form + " needs a number, not " + v);
    }

    private static double asDouble(Value v) {
        return v instanceof Value.Int i ? (double) i.value() : ((Value.Dbl) v).value();
    }

    /** {@code lists/unit-factors.yaml}: factor per "from\0to". */
    private static Map<String, Value> unitFactors() {
        Map<String, Value> m = unitFactors;
        if (m == null) {
            try (InputStream in = Evaluator.class.getClassLoader().getResourceAsStream(UNIT_FACTORS)) {
                if (in == null) {
                    throw new IllegalStateException("Not in the submission vocabulary: " + UNIT_FACTORS);
                }
                JsonNode root = new YAMLMapper().readTree(in);
                Map<String, Value> factors = new java.util.HashMap<>();
                for (JsonNode f : root.get("factors")) {
                    factors.put(f.get("from").asText() + "\u0000" + f.get("to").asText(), checkNumber(Value.of(f.get("factor")), "factor"));
                }
                m = Map.copyOf(factors);
                unitFactors = m;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return m;
    }
}
