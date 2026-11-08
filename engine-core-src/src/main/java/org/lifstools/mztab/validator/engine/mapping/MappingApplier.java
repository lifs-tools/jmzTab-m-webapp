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
package org.lifstools.mztab.validator.engine.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import org.lifstools.mztab.submission.Vocabulary;
import org.lifstools.mztab.submission.mapping.Evaluator;
import org.lifstools.mztab.submission.mapping.Mapping;
import org.lifstools.mztab.submission.mapping.MappingLoader;
import org.lifstools.mztab.submission.mapping.MappingRequestException;
import org.lifstools.mztab.submission.mapping.Notes;
import org.lifstools.mztab.submission.mapping.Numbers;
import org.lifstools.mztab.submission.mapping.Render;
import org.lifstools.mztab.submission.mapping.Scope;
import org.lifstools.mztab.submission.mapping.Value;
import org.lifstools.mztab.submission.mapping.functions.Functions;
import org.lifstools.mztab.validator.engine.api.LineEdit;
import org.lifstools.mztab.validator.engine.api.MappingApplyRequest;
import org.lifstools.mztab.validator.engine.api.MappingApplyResult;
import org.lifstools.mztab.validator.engine.api.Suggestion;
import org.lifstools.mztab.validator.engine.api.SuggestionState;

/**
 * Applies an output mapping of the submission vocabulary to a working copy's text, as line edits (design §5.7, N7).
 *
 * <p>The mapping's lines and tables are evaluated first, with their references symbolic. The elements are then
 * given indexes through {@link CopyIndex}, in the order they are written: {@code sample}, {@code assay} and
 * {@code study_variable} by the value of {@code element[n]}, {@code contact} by {@code contact[n]-name}, and
 * {@code ms_run} by the name its references give it. No line names a run, so runs are paired with names by order:
 * the run written as {@code ms_run[n]} is the {@code n}th distinct name among the {@code ref-to ms_run} values, in
 * order of first reference, where a reference without a name takes a place of its own (and its run has no name).
 * A reference without a name renders empty, so a line holding only such references is not written. Runs and the assays that refer to them must therefore be written over the same list.
 * An element the copy has by that name keeps its index, any other takes the next free one,
 * and every key, reference and abundance column of an element is written with the same index. Other keys keep the
 * index the mapping gives them. Last, the lines are compared with the copy:
 * <ul>
 * <li>a key the copy lacks is a new line, after the copy's last metadata line with the same key prefix, else after
 * its last metadata line;</li>
 * <li>a key the copy has with the same value is unchanged;</li>
 * <li>a key the copy has with another value is replaced when it is a singleton (e.g. {@code title}) or belongs to an
 * element the copy has by name, and otherwise conflicts and is left alone;</li>
 * <li>a table whose section the copy lacks is inserted after a blank line following the metadata; one the copy has
 * conflicts and is left alone.</li>
 * </ul>
 */
public final class MappingApplier {

    /** Keys that occur once per file: an existing value is replaced. */
    private static final Set<String> SINGLETONS = Set.of("mzTab-version", "mzTab-ID", "title", "description",
            "quantification_method", "small_molecule-quantification_unit", "small_molecule_feature-quantification_unit",
            "small_molecule-identification_reliability");
    /** The elements whose indexes are allocated by name. */
    private static final Set<String> ALLOCATED = Set.of("sample", "assay", "study_variable", "ms_run", "contact");
    private static final Map<String, String> COLUMN_ELEMENTS = Map.of("abundance_assay", "assay",
            "abundance_study_variable", "study_variable", "abundance_variation_study_variable", "study_variable");

    /** Guarded by the class: see {@link #vocabulary()}. */
    private static Vocabulary vocabulary;

    private final Function<String, Mapping> mappings;

    /** Applies the mappings of the submission vocabulary. */
    public MappingApplier() {
        this(MappingLoader::load);
    }

    /** Applies the mappings {@code mappings} finds by id; for tests. */
    MappingApplier(Function<String, Mapping> mappings) {
        this.mappings = Objects.requireNonNull(mappings);
    }

    /** A metadata line as written: key and cell text. */
    record KeyValue(String key, String value) {
    }

    /** A line to insert after line {@code anchor} of the copy (0: at the start). */
    private record Insert(int anchor, String text) {
    }

    /**
     * Applies the output mapping {@code r.mapping()} in version {@code r.mappingVersion()}.
     *
     * @param engineVersions the mzTab-M versions this engine serves
     * @throws MappingRequestException when the mapping is unknown, is not an output mapping, has another version or
     *     targets a version this engine does not serve, and when a cell would hold a tab or line break
     * @throws IllegalArgumentException when the mapping cannot be applied to the instance otherwise
     */
    public MappingApplyResult apply(MappingApplyRequest r, Set<String> engineVersions) {
        Mapping mapping = mappings.apply(r.mapping());
        if (mapping.kind() != Mapping.Kind.OUTPUT) {
            throw new MappingRequestException("mapping " + mapping.id() + " is not an output mapping");
        }
        if (!mapping.version().equals(r.mappingVersion())) {
            throw new MappingRequestException("mapping " + mapping.id() + " has version " + mapping.version() + ", not "
                    + r.mappingVersion());
        }
        if (!engineVersions.contains(mapping.target())) {
            throw new MappingRequestException("mapping " + mapping.id() + " targets " + mapping.target()
                    + "; this engine serves " + String.join(", ", new TreeSet<>(engineVersions)));
        }
        return new Run(mapping, r).result();
    }

    /** The vocabulary, loaded once on first use; a failed load is tried again on the next use. */
    private static synchronized Vocabulary vocabulary() {
        if (vocabulary == null) {
            vocabulary = Vocabulary.load();
        }
        return vocabulary;
    }

    /** One application: the copy, the indexes given out, and what is written. */
    private static final class Run {

        private final Mapping mapping;
        private final MappingApplyRequest request;
        private final CopyIndex copy;
        private final Notes notes = new Notes();
        /** The index of each element the mapping writes, by its key as the mapping numbers it, e.g. {@code sample[1]}. */
        private final Map<String, Integer> indexes = new HashMap<>();
        /** The elements the copy has by name, by their key in the copy, e.g. {@code sample[3]}. */
        private final Set<String> matched = new HashSet<>();

        Run(Mapping mapping, MappingApplyRequest request) {
            this.mapping = mapping;
            this.request = request;
            this.copy = new CopyIndex(Objects.requireNonNullElse(request.text(), ""));
        }

        MappingApplyResult result() {
            JsonNode instance = request.instance() == null ? JsonNodeFactory.instance.objectNode() : request.instance();
            Scope scope = Scope.root(instance, Objects.requireNonNullElse(request.parameters(), Map.of()), notes);
            Evaluator evaluator = new Evaluator(mapping.let(), vocabulary(), new Functions());
            List<Evaluator.MetadataLine> lines = evaluator.metadata(mapping.metadata(), scope);
            List<Evaluator.Table> tables = mapping.tables().stream().map(t -> evaluator.table(t, scope)).toList();
            allocate(lines, tables);

            List<KeyValue> written = new ArrayList<>();
            for (Evaluator.MetadataLine line : lines) {
                String value = cell(line.value());
                if (!value.isEmpty()) {
                    written.add(new KeyValue(key(line.key()), value));
                }
            }
            noteNullLocations(written);
            if (request.provenance()) {
                Provenance.Entry p = Provenance.of(copy, written, vocabulary().version(), mapping,
                        request.inputMapping());
                written.addAll(p.lines());
                if (p.existing()) {
                    matched.add("software[" + p.index() + "]");
                }
            }
            written.addAll(CvDeclarations.declare(written.stream().map(KeyValue::value).toList(), mapping, copy, notes));

            List<LineEdit> edits = new ArrayList<>();
            List<Insert> inserts = new ArrayList<>();
            List<Suggestion> suggestions = new ArrayList<>();
            metadata(written, edits, inserts, suggestions);
            tables(tables, inserts, suggestions);
            inserts.sort((a, b) -> Integer.compare(a.anchor(), b.anchor()));
            for (int i = 0; i < inserts.size(); i++) {
                edits.add(new LineEdit.InsertLine(inserts.get(i).anchor() + i + 1L, inserts.get(i).text()));
            }
            return new MappingApplyResult(edits, suggestions, notes.all(), applied(edits));
        }

        /**
         * One note naming every run written with the location {@code null} (N13): the mapping found no location for
         * it.
         */
        private void noteNullLocations(List<KeyValue> written) {
            List<String> runs = new ArrayList<>();
            for (KeyValue kv : written) {
                Matcher m = CopyIndex.INDEXED.matcher(kv.key());
                if (m.matches() && "ms_run".equals(m.group(1)) && "-location".equals(m.group(3)) && "null".equals(kv.value())) {
                    runs.add(m.group(2));
                }
            }
            if (!runs.isEmpty()) {
                notes.add("ms_run[" + String.join(",", runs) + "]-location is null: no location was given");
            }
        }

        // -----------------------------------------------------------------------------------------------------------
        // Indexes

        /** Gives every element the mapping writes or refers to its index, in the order they are written. */
        private void allocate(List<Evaluator.MetadataLine> lines, List<Evaluator.Table> tables) {
            Map<String, String> names = new HashMap<>();
            List<String> runs = runNames(lines);
            for (int i = 0; i < runs.size(); i++) {
                if (runs.get(i) != null) {
                    names.put("ms_run[" + (i + 1) + "]", runs.get(i));
                }
            }
            for (Evaluator.MetadataLine line : lines) {
                Matcher m = CopyIndex.INDEXED.matcher(line.key());
                if (m.matches() && ALLOCATED.contains(m.group(1)) && !"ms_run".equals(m.group(1))
                        && ("contact".equals(m.group(1)) ? "-name".equals(m.group(3)) : m.group(3).isEmpty())) {
                    String name = name(line.value());
                    if (name != null) {
                        names.putIfAbsent(m.group(1) + "[" + Integer.parseInt(m.group(2)) + "]", name);
                    }
                }
            }
            for (Evaluator.MetadataLine line : lines) {
                Matcher m = CopyIndex.INDEXED.matcher(line.key());
                if (m.matches() && ALLOCATED.contains(m.group(1))) {
                    String element = m.group(1);
                    indexes.computeIfAbsent(element + "[" + Integer.parseInt(m.group(2)) + "]", written -> {
                        String name = names.get(written);
                        if (name == null) {
                            return copy.reserve(element);
                        }
                        int index = copy.allocate(element, name);
                        if (copy.indexOf(element, name) > 0) {
                            matched.add(element + "[" + index + "]");
                        }
                        return index;
                    });
                }
                refs(line.value(), this::index);
            }
            for (Evaluator.Table table : tables) {
                table.rows().forEach(row -> row.forEach(v -> refs(v, this::index)));
            }
        }

        /** The cell of a reference: the element with its index, or empty for a reference without a name. */
        private String ref(Value.Ref ref) {
            Integer index = index(ref);
            return index == null ? "" : ref.element() + "[" + index + "]";
        }

        /**
         * The runs the metadata values refer to, in order of first reference: each distinct name once, and null for
         * each reference without a name, which keeps its place so the runs after it pair with the right names.
         */
        private static List<String> runNames(List<Evaluator.MetadataLine> lines) {
            List<String> names = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (Evaluator.MetadataLine line : lines) {
                refs(line.value(), ref -> {
                    if ("ms_run".equals(ref.element())) {
                        String name = name(ref.name());
                        if (name == null || seen.add(name)) {
                            names.add(name);
                        }
                    }
                });
            }
            return names;
        }

        private static void refs(Value v, Consumer<Value.Ref> each) {
            if (v instanceof Value.Ref ref) {
                each.accept(ref);
            } else if (v instanceof Value.ListV list) {
                list.value().forEach(item -> refs(item, each));
            }
        }

        /**
         * The index of the element a reference names, or null for a reference without a name: it names no element,
         * so it renders empty, and a line holding only such references is not written.
         */
        private Integer index(Value.Ref ref) {
            if (!ALLOCATED.contains(ref.element())) {
                throw new IllegalArgumentException("ref-to " + ref.element() + " is not resolved by this engine; it resolves "
                        + String.join(", ", new TreeSet<>(ALLOCATED)));
            }
            String name = name(ref.name());
            if (name == null) {
                return null;
            }
            return copy.allocate(ref.element(), name);
        }

        /** An element's name as cell text, or null when it has none (Null, or a text that renders empty). */
        private static String name(Value v) {
            String name = switch (v) {
                case Value.Null n -> "";
                case Value.Text t -> Render.text(t.value());
                case Value.Int i -> Numbers.python(i);
                case Value.Dbl d -> Numbers.python(d);
                default -> throw new MappingRequestException("An element's name must be a text or a number, not " + v);
            };
            return name.isEmpty() ? null : name;
        }

        /** A key, or an abundance column name, with the index given to its element. */
        private String key(String key) {
            Matcher m = CopyIndex.INDEXED.matcher(key);
            if (!m.matches()) {
                return key;
            }
            String element = COLUMN_ELEMENTS.getOrDefault(m.group(1), m.group(1));
            if (!ALLOCATED.contains(element) || COLUMN_ELEMENTS.containsKey(m.group(1)) && !m.group(3).isEmpty()) {
                return key;
            }
            Integer index = indexes.get(element + "[" + Integer.parseInt(m.group(2)) + "]");
            return index == null ? key : m.group(1) + "[" + index + "]" + m.group(3);
        }

        // -----------------------------------------------------------------------------------------------------------
        // Cells

        /** A value as a metadata cell ({@code schema/mapping-language.md}, Cell rendering); empty when nothing is written. */
        private String cell(Value v) {
            return switch (v) {
                case Value.Null n -> "";
                case Value.Text t -> Render.text(t.value());
                case Value.Int i -> Numbers.python(i);
                case Value.Dbl d -> Numbers.python(d);
                case Value.ParamV p -> Objects.requireNonNullElse(Render.param(p.value()), "");
                case Value.Ref ref -> ref(ref);
                case Value.ListV list -> String.join("|", list.value().stream().map(this::cell).filter(c -> !c.isEmpty()).toList());
                case Value.Obj o -> throw new MappingRequestException("An object cannot be written into a cell: " + o);
            };
        }

        /** A value as a table cell, where Null is an error. */
        private String tableCell(Value v, String column) {
            if (v == Value.Null.INSTANCE) {
                throw new MappingRequestException("The column " + column + " has a Null cell; use number to write null");
            }
            return cell(v);
        }

        /**
         * {@code text}, when it holds no tab, CR or LF: a key or cell is never rewritten here, so clean input is written
         * exactly as the converter writes it, but text that would split a cell or a line is refused.
         *
         * @throws MappingRequestException naming {@code key} otherwise
         */
        private static String checked(String key, String text) {
            if (text.indexOf('\t') >= 0 || text.indexOf('\r') >= 0 || text.indexOf('\n') >= 0) {
                throw new MappingRequestException(key + ": value contains a tab or line break");
            }
            return text;
        }

        // -----------------------------------------------------------------------------------------------------------
        // Edits

        private void metadata(List<KeyValue> written, List<LineEdit> edits, List<Insert> inserts, List<Suggestion> suggestions) {
            for (KeyValue kv : written) {
                checked(kv.key(), kv.key());
                checked(kv.key(), kv.value());
                int line = copy.metadataLine(kv.key());
                SuggestionState state;
                if (line == 0) {
                    int anchor = copy.lastMetadataLine(CopyIndex.prefixOf(kv.key()));
                    inserts.add(new Insert(anchor > 0 ? anchor : copy.lastMetadataLine(), "MTD\t" + kv.key() + "\t" + kv.value()));
                    state = SuggestionState.NEW;
                } else if (kv.value().equals(copy.metadataValue(kv.key()))) {
                    state = SuggestionState.UNCHANGED;
                } else if (SINGLETONS.contains(kv.key()) || matched.contains(elementOf(kv.key()))) {
                    edits.add(new LineEdit.SetCell(line, 2, kv.value()));
                    state = SuggestionState.REPLACES_EXISTING;
                } else {
                    state = SuggestionState.CONFLICTS;
                }
                suggestions.add(new Suggestion(kv.key(), kv.value(), state));
            }
        }

        /** The element a key belongs to, e.g. {@code sample[2]} for {@code sample[2]-species[1]}, or the key. */
        private static String elementOf(String key) {
            Matcher m = CopyIndex.INDEXED.matcher(key);
            return m.matches() ? m.group(1) + "[" + m.group(2) + "]" : key;
        }

        private void tables(List<Evaluator.Table> tables, List<Insert> inserts, List<Suggestion> suggestions) {
            List<List<String>> added = new ArrayList<>();
            for (Evaluator.Table table : tables) {
                List<String> columns = table.columns().stream().map(this::key).toList();
                columns.forEach(column -> checked(table.header(), column));
                List<String> rows = new ArrayList<>();
                for (List<Value> row : table.rows()) {
                    List<String> cells = new ArrayList<>(row.size());
                    for (int c = 0; c < row.size(); c++) {
                        cells.add(checked(columns.get(c), tableCell(row.get(c), columns.get(c))));
                    }
                    rows.add(String.join("\t", cells));
                }
                boolean exists = copy.hasSection(table.header()) || copy.hasSection(table.section());
                SuggestionState state = exists ? SuggestionState.CONFLICTS : SuggestionState.NEW;
                suggestions.add(new Suggestion(table.header(), String.join("\t", columns), state));
                for (int r = 0; r < rows.size(); r++) {
                    suggestions.add(new Suggestion(table.section() + "[" + (r + 1) + "]", rows.get(r), state));
                }
                if (exists) {
                    notes.add(table.section() + " already exists; table imports into an existing section come with the "
                            + "summary-table import");
                } else {
                    List<String> section = new ArrayList<>();
                    section.add(table.header() + "\t" + String.join("\t", columns));
                    rows.forEach(row -> section.add(table.section() + "\t" + row));
                    added.add(section);
                }
            }
            if (added.isEmpty()) {
                return;
            }
            int last = copy.lastMetadataLine();
            boolean blank = last < copy.lineCount() && copy.line(last + 1).isEmpty();
            int anchor = blank ? last + 1 : last;
            List<String> block = new ArrayList<>();
            for (int i = 0; i < added.size(); i++) {
                if (i > 0 || !blank) {
                    block.add("");
                }
                block.addAll(added.get(i));
            }
            if (anchor < copy.lineCount() && !copy.line(anchor + 1).isEmpty()) {
                block.add("");
            }
            block.forEach(text -> inserts.add(new Insert(anchor, text)));
        }

        /** The copy's text with {@code edits} applied; an inserted line ends as the copy's first line does, else with LF. */
        private String applied(List<LineEdit> edits) {
            List<String> lines = new ArrayList<>();
            List<String> endings = new ArrayList<>();
            for (int n = 1; n <= copy.lineCount(); n++) {
                lines.add(copy.line(n));
                endings.add(copy.ending(n));
            }
            String terminator = copy.lineCount() > 0 && !copy.ending(1).isEmpty() ? copy.ending(1) : "\n";
            for (LineEdit edit : edits) {
                switch (edit) {
                    case LineEdit.SetCell s -> {
                        int i = (int) s.line() - 1;
                        List<String> cells = new ArrayList<>(List.of(lines.get(i).split("\t", -1)));
                        if (s.cell() == cells.size()) {
                            cells.add(s.text());
                        } else {
                            cells.set(s.cell(), s.text());
                        }
                        lines.set(i, String.join("\t", cells));
                    }
                    case LineEdit.InsertLine ins -> {
                        int i = (int) ins.before() - 1;
                        if (i == lines.size() && i > 0 && endings.get(i - 1).isEmpty()) {
                            endings.set(i - 1, terminator);
                        }
                        lines.add(i, ins.text());
                        endings.add(i, terminator);
                    }
                    case LineEdit.InsertColumn c -> throw new IllegalStateException("An output mapping inserts no column: " + c);
                }
            }
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < lines.size(); i++) {
                text.append(lines.get(i)).append(endings.get(i));
            }
            return text.toString();
        }
    }
}
