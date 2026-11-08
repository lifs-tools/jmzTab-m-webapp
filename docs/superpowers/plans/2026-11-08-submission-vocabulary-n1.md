# Submission Vocabulary and Mappings (N-1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publish the LipidCompass v3 field dictionary as a versioned, world-public submission vocabulary with a declarative mapping language. Run the 2.0 output mapping in the 2.0 engine so that the v3 example becomes the same mzTab-M as today's Python converter. Make the v3 workbook build read the published vocabulary.

**Architecture:** A new Maven module `submission-vocabulary` (artifactId `mztab-validator-submission-vocabulary`) holds three things:
- the data (vocabulary, lists, mappings, schemas, examples, CC-BY-4.0);
- a Java library that loads mappings and evaluates their expressions, with no mzTab-M knowledge;
- the named functions (Goslin, factor combinations, formula check).

The mzTab-M side lives in `engine-core-src`. It turns evaluated rules into lines, resolves references, declares CVs, finds existing elements, and returns line edits through `POST /engine/v1/mappings/apply`. The webapp server only serves the files read-only. lipidcompass-submissions vendors the vocabulary and builds the v3 workbook from it.

**Tech Stack:** Java 24, Maven, Spring Boot (engines and server), Jackson (JSON and YAML), `com.networknt:json-schema-validator` (Draft 2020-12), `org.lifs-tools:jgoslin-parsers:2.2.1` (as `lipidcompass/pom.xml:123`), JUnit 5 with AssertJ. Python 3.12+ with `uv` in lipidcompass-submissions.

**Spec:** `docs/superpowers/specs/2026-11-08-new-mztabm-file-design.md`, chiefly §3, §4 (N7, N10, N13–N15), §5.2, §5.3, §6 N-1 and §8. Read it before any task.

**Reference code (pinned):** lipidcompass-submissions `origin/master` **bb8dcbf**. `conv:NNN` means `tools/convert_submission_template.py` at bb8dcbf. The reference outputs were produced at bb8dcbf with Python 3.13.13 and validate with jmzTab-m 1.0.8-SNAPSHOT with no errors or warnings, only Info-2052 and Info-2057.

## Global Constraints

- **Vocabulary 1.0.0** contains every v3 sheet and field id unchanged. New groups and fields are additive and optional (N15).
- **What the workbook renders:** only the groups with `workbook: true` (N15).
- **Licence:** data files under `submission-vocabulary/` are CC-BY-4.0. The Java code is Apache-2.0.
- **Expressions and functions:** only the expression forms in `schema/mapping-language.md` (design §5.3, as amended 2026-11-08) and the named functions `goslin-parse`, `factor-combinations`, `formula-check` and `index-allocate`. Adding either is a design change.
- **mzTab-M keys are read and written only in the engines** (engines design §2, N7).
- **An engine applies only output mappings whose `target` is one of its own mzTab-M versions.** Otherwise it answers 400.
- **`ms_run[n]-location` without a runs group:** the dataset's `raw_data_location`, else the study's first `repository_links` entry, else `null`, plus a note. Never `file://<sample>` (N13). The v3 example has a repository link, so its output equals the converter's.
- **Provenance:** the webapp is `software[k]`, with `software[k]-setting[1..3]` = `vocabulary 1.0.0`, `mztab-2.0.0-M 1.0.0` and `v3-workbook 1.0.0`. Lines with the key prefix `software[3]` are the only allowed difference in the v3 equivalence test.
- **Floats** are held as `double`. Integers from JSON integer nodes are held as `long`. The Jackson `IntNode`/`DoubleNode` distinction is kept.
- **Branches:**
  - webapp: `feat/submission-vocabulary`, from `feat/validator-spa`;
  - lipidcompass-submissions: `feat/vocabulary-from-webapp`, from `origin/master` (bb8dcbf; local `master` is stale).

## Review Focus

1. **Python `str()` number parity:**
   - `29.7`, `0.1`, `702.567575545`, `1e-05`, `1.5e-05` and `1e+16` render as written;
   - `1000000000000000.0` renders as `1000000000000000.0`;
   - the long `45` renders as `45`.

   Owned by Task 2: `NumbersTest`.
2. **Free text with commas, double quotes, tabs, line breaks or non-ASCII characters** (µmol/L, Ä), and a parameter with neither accession nor name (rendered as nothing, `conv:122-126`). Owned by Task 2: `RenderTest`.
3. **Aggregates with Python's arithmetic:**
   - the mean of the example's SML 3 group is `154.43099999999998`;
   - `fmean([2, 2])` is `2.0`;
   - one member gives a null variation;
   - all members null gives a null mean and variation.

   Owned by Task 3: `AggregateTest`.
4. **Goslin masses as pygoslin under Python 3.12 or later:** `759.57780591`, `702.567575545`, and `752.606093048` for the instance name `PC 15:0-18:1(d7)` (no plain formula). jgoslin's `getMass()` would give `759.5778059099999`. Owned by Task 3: `GoslinFunctionTest`.
5. **Merging into a copy where `sample[1]` is `P009`.** Expected:
   - P001 becomes `sample[2]`;
   - its assay's `-sample_ref` says `sample[2]`;
   - `study_variable` `-assay_refs` name the reassigned assays;
   - the existing SML section is reported as a conflict, not overwritten.

   Owned by Task 4: `MappingApplierMergeTest`.

---

### Task 1: The `submission-vocabulary` module, vocabulary 1.0.0 and lists

**Files:**
- Create: `submission-vocabulary/pom.xml` (artifactId `mztab-validator-submission-vocabulary`, parent `mztab-validator-webapp-parent`). Its resources are the folders `vocabulary`, `lists`, `mappings`, `schema`, `examples` and the files `README.md` and `LICENSE`, all with target path `submission-vocabulary/`.
- Modify: root `pom.xml`, adding `<module>submission-vocabulary</module>` before `engine-api`.
- Create the data files:
  - `submission-vocabulary/README.md` (purpose, versioning, licence, how to propose changes; from design §5.2–§5.3);
  - `submission-vocabulary/LICENSE` (CC-BY-4.0 legal code);
  - `submission-vocabulary/vocabulary/vocabulary.yaml`;
  - `submission-vocabulary/lists/<list>.yaml`, one per list in bb8dcbf `templates/v3/submission-template-schema.json` `lists`;
  - `submission-vocabulary/schema/vocabulary.schema.json` and `list.schema.json`.
- Create the Java and test files:
  - `submission-vocabulary/src/main/java/org/lifstools/mztab/submission/Vocabulary.java`;
  - Test: `submission-vocabulary/src/test/java/org/lifstools/mztab/submission/VocabularyFilesTest.java`;
  - Test resource: `submission-vocabulary/src/test/resources/v3-field-ids.txt` (`group.field` per line, generated once from that bb8dcbf schema's `sheets`).

**Interfaces:**
- **Produces:**
  - `Vocabulary.load(): Vocabulary`.
  - `version(): String`, which returns `"1.0.0"`.
  - `group(String id): Group`, where `Group(String id, boolean workbook, List<Field> fields, @Nullable String help)`.
  - `list(String name): ValueList`, where `ValueList(String name, List<Entry> entries)`.
  - `encodingOf(String text): Optional<Param>`, the agreed encoding of a text entry in any list.
  - `Entry` is one of `Entry.Term(String cv, String accession, String label)` or `Entry.Text(String text, @Nullable String note, @Nullable Param encoding)`.
  - `Param(String cv, String accession, String name, String value)`.

**`vocabulary.yaml` structure** (top-level keys):
- `vocabulary: {id: submission-vocabulary, version: 1.0.0, licence: CC-BY-4.0, origin: "lipidcompass-submission-template 3.0.0 (lipidcompass-submissions bb8dcbf)"}`;
- `conventions`: verbatim from the v3 schema;
- `groups`:
  - `study`, `contacts`, `datasets`, `samples`, `factors`, `lipids`: `workbook: true`, their v3 fields with every key verbatim (`id`, `label`, `required`, `list`, `type`, `version_of`, `mztab`, `ontologies`, `help`, `example`);
  - `quantities`: `workbook: true`, `help` verbatim, no fields;
  - `runs`: `workbook: false`, with the optional fields `file_name`, `file_format`, `paired_file`, `sample_name`, `assay`, `fraction_of`, `scan_polarity` (list `scan_polarity`), `instrument_name` (list), `dissociation_method` (list). Labels and help come from design §5.2.

The v3 `template` block (id, version, title, contact) is **not** part of the vocabulary. It stays in lipidcompass-submissions (Task 7).

**List files.** Each holds the bb8dcbf `options.yaml` entries in order:
- **CV entries** carry the accession and the label from `options.resolved.json`.
- **Text entries** keep `text` and `note`.
- **`extra_lists`** become list files too.
- **The mol% text entry** gets `encoding: {cv: UO, accession: UO:0000076, name: mole fraction, value: mol%}`, from bb8dcbf `TEXT_TERMS` (`conv:81`).

- [ ] **Step 1: Write the failing test**

```java
@Test void everyFileValidatesAgainstItsSchema()        // vocabulary.yaml and every lists/*.yaml: zero schema errors
@Test void containsEveryV3FieldUnchanged()             // group.field ids of workbook=true groups == v3-field-ids.txt; each field's keys equal the v3 schema's
@Test void everyListReferenceResolves()                // each field.list names an existing list file
@Test void runsGroupIsNotRenderedInTheWorkbook()       // assertThat(v.group("runs").workbook()).isFalse()
@Test void molPercentHasItsAgreedEncoding()            // encodingOf("mol% (mole percent of the measured lipidome)") == Param("UO","UO:0000076","mole fraction","mol%")
@Test void versionIs100()                              // assertThat(Vocabulary.load().version()).isEqualTo("1.0.0")
```

- [ ] **Step 2: Run the test to verify it fails.** Run `./mvnw -pl submission-vocabulary test -Dtest=VocabularyFilesTest`. Expected: a compilation failure, because `Vocabulary` is not defined.
- [ ] **Step 3: Write the data files and `Vocabulary`.** Convert from bb8dcbf with a throwaway script; do not commit it.
- [ ] **Step 4: Run the test to verify it passes.** Same command. Expected: 6 tests pass.
- [ ] **Step 5: Commit** with the message `submission-vocabulary: vocabulary 1.0.0 and value lists from the v3 template`.

### Task 2: The mapping language: schema, loader, expressions and rendering

**Files:**
- Create: `submission-vocabulary/schema/mapping.schema.json` and `submission-vocabulary/schema/mapping-language.md`. The latter is normative: one section per expression form and rule kind, each with an example.
- Create: `submission-vocabulary/src/main/java/org/lifstools/mztab/submission/mapping/{Mapping.java, MappingLoader.java, Expr.java, Value.java, Evaluator.java, Scope.java, Notes.java, Render.java, Numbers.java, PyMath.java, Identifiers.java, InvalidMappingException.java}`
- Test: `submission-vocabulary/src/test/java/org/lifstools/mztab/submission/mapping/{MappingLoaderTest, EvaluatorTest, RenderTest, NumbersTest, PyMathTest, IdentifiersTest}.java`

**Interfaces:**
- Consumes: `Vocabulary`, `Param` (Task 1).
- Produces:
  - **Loading.** `MappingLoader.load(String id): Mapping` reads `submission-vocabulary/mappings/{input|output}/<id>.yaml` and validates it against `mapping.schema.json`. A failure throws `InvalidMappingException`, whose message contains the JSON pointer.
  - **`Mapping`:** `Mapping(String id, String version, Kind kind, String vocabulary, @Nullable String target, List<String> parameters, LinkedHashMap<String, CvInfo> cvs, LinkedHashMap<String, Expr> let, List<MetadataRule> metadata, List<TableRule> tables)`.
  - **`CvInfo`:** `CvInfo(String fullName, String version, String uri)`.
  - **`MetadataRule`** is one of:
    - `Line(String key, Expr value)`;
    - `Each(Expr over, String as, @Nullable Expr where, List<MetadataRule> lines)`.
  - **`TableRule`:** `TableRule(String section, String header, Expr rows, String as, List<ColumnRule> columns)`. `section` is `SML`; `header` is `SMH`.
  - **`ColumnRule`** is one of:
    - `Column(String header, Expr value, @Nullable Expr where)`;
    - `EachColumn(Expr over, String as, List<Column> columns)`.

    A `where` false for the table drops the column from the header and every row.
  - **`Value`** is one of:
    - `Null`;
    - `Text(String)`;
    - `Int(long)`;
    - `Dbl(double)`;
    - `Obj(LinkedHashMap<String, Value>)`;
    - `ListV(List<Value>)`;
    - `ParamV(Param)`;
    - `Ref(String element, Value name)`, a symbolic reference resolved by the engine (Task 4).
  - **Evaluation.** `Evaluator.eval(Expr e, Scope s): Value`.
  - **`Scope`:** `Scope.root(JsonNode instance, Map<String,String> parameters, Notes notes)` and `scope.with(String name, Value v, int position)`. `position` is 1-based among the items an `each` or `map` visits.
  - **`Notes`:** `add(String)` and `all(): List<String>`.
  - **Rendering:**
    - `Render.text(String)` collapses whitespace runs to one space and trims (`conv:112`).
    - `Render.quoted(String)` is `text`, then `"`→`'`, then wrapped in `"…"` when it contains `,` (`conv:117`).
    - `Render.param(Param): @Nullable String` follows `conv:122-126`:
      - it is null when both the accession and the name are empty;
      - otherwise `[cv-if-accession, accession, quoted(name), quoted(value)-or-empty]`.
  - **`Numbers.python(Value)`** gives Python `str()`:
    - an `Int` is its digits;
    - a `Dbl` is the shortest round-trip repr, in scientific form when the decimal exponent is < −4 or ≥ 16, with Python's mantissa and exponent style (`1e-05`, `1.5e-05`, `1e+16`);
    - finite integral doubles keep `.0`.
  - **`PyMath`:**
    - `fsum(double[])`: the exactly rounded sum, Shewchuk partials as in CPython `math.fsum`;
    - `fmean(double[])`: `fsum/n`;
    - `stdev(double[])`: the square root, correctly rounded, of the exact rational sample variance. Use `BigDecimal` for the exact variance from the doubles' exact values, and `BigDecimal.sqrt` with ample precision rounded to double, as CPython 3.12 `statistics.stdev`;
    - `neumaierSum(double[])`: as CPython 3.12+ builtin `sum` on floats.
  - **`Identifiers.doi(String): @Nullable String` and `Identifiers.email(String): boolean`** port bb8dcbf `src/curation/identifiers.py` exactly:
    - `DOI_RE`, `DOI_PREFIX` and `DOI_TRAIL` as there;
    - `mztab_doi` rejects results containing `:`;
    - `_EMAIL` as there.

**Expression forms.** Each YAML node has exactly one key. The forms:

| Form | Meaning |
|---|---|
| `literal` | A fixed value. |
| `field: a.b.c` | A path. The first segment is a scope variable, `params`, or a top-level instance key. |
| `ref` | A `let` name. |
| `pos: x` | The position of scope variable `x`. |
| `format: {template, args}` | Placeholders `{0}`, `{1:02}`. |
| `join: {items, sep}` | Empty items are skipped. |
| `first: [..]` | The first non-empty item. |
| `split: {value, sep}` | Parts are trimmed; empty parts are dropped. |
| `when: {if, then, else}` | A conditional. |
| `eq: [a, b]` | Text comparison, case-sensitive. |
| `lower` | Lower case. |
| `not-empty` | A test. |
| `count` | The size of a list. |
| `at: {list, index}` | A 1-based element, or Null when out of range. |
| `concat: [lists]` | Joins lists. |
| `entries` | An object as a `ListV[Obj{key, value}]`. |
| `map: {over, as, value}` | Applies `value` to each item. |
| `find: {in, as, where}` | The first matching item. |
| `filter: {in, as, where}` | All matching items. |
| `term: {value, param-value}` | A CV dict becomes a CV parameter. A text with a vocabulary encoding (`Vocabulary.encodingOf`) becomes that encoding. Other text becomes a user parameter (`conv:129-139`). |
| `cvparam: {cv, accession, name, value}` | A CV parameter from parts. |
| `user-param: {name, value}` | A user parameter. |
| `label` | The CV name, or the text, or `Numbers.python` (`conv:141`). |
| `number` | `Numbers.python`, or the text `null` for Null (`conv:145`). |
| `normalise: {kind: doi\|email, value}` | Applies `Identifiers`; failure gives Null plus a note. |
| `match: {value, regex, group}` | The named group, or Null. |
| `convert-unit: {value, from, to}` | Uses `lists/unit-factors.yaml`. 1.0.0 has nmol/mL→µmol/L and pmol/mL→nmol/L, both with factor 1. |
| `aggregate: {fn: mean\|cv, values}` | `PyMath.fmean`, or `stdev/mean`. Null for fewer than 1 value (mean), fewer than 2 values (cv), or a zero mean. Results are always `Dbl`. |
| `ref-to: {element, name}` | Produces `Value.Ref`. |
| `fn: {name, args}` | A named function (Task 3). |

**Key templates.** In a `Line.key`, `{x#}` is the 1-based position of `x` among the items of its `each` that produced at least one non-empty line. This reproduces the converter's numbering after filtering.

- [ ] **Step 1: Write the failing tests**

```java
// NumbersTest
assertThat(py(new Value.Dbl(29.7))).isEqualTo("29.7");     assertThat(py(new Value.Int(45))).isEqualTo("45");
assertThat(py(new Value.Dbl(45.0))).isEqualTo("45.0");     assertThat(py(new Value.Dbl(1e-5))).isEqualTo("1e-05");
assertThat(py(new Value.Dbl(1.5e-5))).isEqualTo("1.5e-05"); assertThat(py(new Value.Dbl(1e16))).isEqualTo("1e+16");
assertThat(py(new Value.Dbl(1e15))).isEqualTo("1000000000000000.0");
assertThat(py(new Value.Dbl(702.567575545))).isEqualTo("702.567575545");
// PyMathTest
assertThat(PyMath.fmean(new double[]{2, 2})).isEqualTo(2.0);
assertThat(PyMath.fsum(new double[]{0.1, 0.2, 0.3})).isEqualTo(0.6);              // naive sum gives 0.6000000000000001
assertThat(PyMath.stdev(new double[]{1.0, 3.0})).isEqualTo(1.4142135623730951);
// RenderTest
assertThat(Render.param(new Param("", "", "chromatography column", "Waters Acquity UPLC BEH C18, 2.1 x 100 mm, 1.7 um")))
    .isEqualTo("[, , chromatography column, \"Waters Acquity UPLC BEH C18, 2.1 x 100 mm, 1.7 um\"]");
assertThat(Render.param(new Param("MS", "MS:1003966", "LipidSearch", "4.2"))).isEqualTo("[MS, MS:1003966, LipidSearch, 4.2]");
assertThat(Render.param(new Param("NCIT", "", "x", ""))).isEqualTo("[, , x, ]");
assertThat(Render.param(new Param("", "", "", "v"))).isNull();
assertThat(Render.quoted("say \"hi\",\tµmol/L\n")).isEqualTo("\"say 'hi', µmol/L\"");
// IdentifiersTest: the parametrised cases of bb8dcbf tests/unit/test_identifiers.py, verbatim (10 DOI inputs -> "10.1234/abc.5",
//   9 -> null; 3 valid and 9 invalid e-mails)
// EvaluatorTest, one test per form, including:
//   join([literal "a", Null, literal "b"], "|") -> "a|b";   first([Null, "", "x"]) -> "x";   count([1,2,3]) -> Int 3
//   at([a,b], 1) -> a;  at([a], 2) -> Null;  concat([[a],[b,c]]) -> [a,b,c];  entries({"BMI":29.7}) -> [{key:"BMI",value:29.7}]
//   map over [1,2] as x value format("assay[{0}]", [pos x]) -> ["assay[1]","assay[2]"]
//   term({"text":"mol% (mole percent of the measured lipidome)"}) -> ParamV(UO, UO:0000076, mole fraction, mol%)
//   term({"text":"Lipidome study"}) -> ParamV("", "", "Lipidome study", "")
//   format("{0}-{1:02}", ["LCS-99999", 1]) -> "LCS-99999-01"
// MappingLoaderTest: a minimal valid mapping loads; an unknown expression key throws InvalidMappingException with "/metadata/0/value"
```

- [ ] **Step 2: Run the tests to verify they fail.** Run `./mvnw -pl submission-vocabulary test`. Expected: a compilation failure.
- [ ] **Step 3: Implement the classes, the schema and `mapping-language.md`.**
- [ ] **Step 4: Run the tests to verify they pass.** Same command. Expected: all pass.
- [ ] **Step 5: Commit** with the message `submission-vocabulary: mapping language, loader, expressions and Python-exact rendering`.

### Task 3: Named functions

**Files:**
- Create: `submission-vocabulary/src/main/java/org/lifstools/mztab/submission/mapping/functions/{Functions.java, GoslinParse.java, FactorCombinations.java, FormulaCheck.java}`
- Modify: `submission-vocabulary/pom.xml`, adding `org.lifs-tools:jgoslin-parsers:2.2.1`.
- Test: `.../functions/{GoslinFunctionTest, FactorCombinationsTest, FormulaCheckTest, AggregateTest}.java`

**Interfaces:**
- Consumes: `Value`, `Scope`, `Evaluator`, `Notes`, `PyMath`, `Numbers` (Task 2).
- Produces: `Functions.call(String name, List<Value> args, Notes notes): Value`, with these functions:
  - **`goslin-parse(name)`** returns `Obj{normalised: Text, formula: Text|Null, mass: Dbl}`, or Null when jgoslin cannot parse the name.
    - `normalised` is jgoslin's `getLipidString()`.
    - `formula` is jgoslin's sum formula when it matches `(?:[A-Z][a-z]?\d*)+`, else Null (`conv:291-296`).
    - **`mass` is not `getMass()`.** It is `PyMath.neumaierSum` over the element masses (`Elements.ELEMENT_MASSES[e] × count`) of the neutral lipid's `getElements()`, in `Element` enum order, as pygoslin's `LipidAdduct.get_mass` computes it under CPython 3.12+.
  - **`factor-combinations(sampleNames, samplesByName, factors)`** returns `ListV[Obj{key: ListV[Obj{factor, value}], members: ListV[Int]}]`, ported from `Converter.study_variables` (`conv:333-340`) and `factor_value` (`conv:169-173`), with these rules:
    - members are **1-based** assay positions;
    - primary factors are those with `primary.text == "yes"`;
    - `sex`, `age` and `disease` are read from the sample record, and other factors from `sample.factors`, case-insensitively;
    - a missing value becomes `{"text":"not specified"}`;
    - groups appear in order of first appearance.
  - **`formula-check(sheet, name, parsed, stated)`** returns `Obj{formula, mass}`, exactly as `Converter.formula` (`conv:291-309`), notes included.
- **The `spring-core` dependency.** jgoslin-parsers 2.2.1 declares `spring-core` 5.3.26 at compile scope. The root pom's Spring Boot dependency management must override it in every module that takes this artifact, as LipidCompass relies on. Step 4 verifies that.

- [ ] **Step 1: Write the failing tests**

```java
// GoslinFunctionTest (expected values: bb8dcbf converter output for the v3 example)
assertThat(goslin("PC 34:1")).isEqualTo(obj("PC 34:1", "C42H82NO8P", 759.57780591));
assertThat(goslin("SM 34:1;O2")).isEqualTo(obj("SM 34:1;O2", "C39H79N2O6P", 702.567575545));
assertThat(goslin("PC 15:0-18:1(d7)")).isEqualTo(obj("PC 15:0_18:1[M[2]H7]", null, 752.606093048));
assertThat(goslin("not a lipid")).isEqualTo(Value.Null.INSTANCE);
// every SML theoretical_neutral_mass of both expected files equals Numbers.python(goslin(name).mass) for its original name
// FactorCombinationsTest
//   no primary factor -> one group, key [], members [1..n]
//   primary 'disease' with T2DM,T2DM,Control -> [T2DM:{1,2}], [Control:{3}] in that order
//   a sample without the factor -> value {"text":"not specified"}
// AggregateTest (Value-level, through Evaluator aggregate)
//   mean of the SML 3 / study_variable values of LCS-99999-01 -> Numbers.python == "154.43099999999998"
//   mean [2, 2] -> "2.0";  cv of one value -> Null;  mean and cv of [Null, Null] -> Null
// FormulaCheckTest: stated "C42H82NO8P" for PC 34:1 -> (stated, mass); stated "C40H80NO8P" -> (stated, Null) plus note
//   "Q_D1: sum formula C40H80NO8P of 'PC 34:1' does not match the name (C42H82NO8P, Goslin); the stated formula is written, without a mass"
```

- [ ] **Step 2: Run the tests to verify they fail.** Run `./mvnw -pl submission-vocabulary test -Dtest='GoslinFunctionTest,FactorCombinationsTest,FormulaCheckTest,AggregateTest'`. Expected: a compilation failure.
- [ ] **Step 3: Implement the three functions and `Functions.call`.**
- [ ] **Step 4: Run the tests to verify they pass.**
  - Same command. Expected: all pass.
  - Then run `./mvnw -pl submission-vocabulary dependency:tree -Dincludes=org.springframework:spring-core`. Expected: the Boot-managed version, not 5.3.26.
- [ ] **Step 5: Commit** with the message `submission-vocabulary: goslin-parse (pygoslin-exact mass), factor-combinations and formula-check`.

### Task 4: Apply an output mapping in the engine

**Files:**
- Modify: `engine-parent/pom.xml`, adding the dependency `mztab-validator-submission-vocabulary`. Both engines need it, because `engine-core-src` is compiled in each.
- Create: `engine-api/src/main/java/org/lifstools/mztab/validator/engine/api/{MappingApplyRequest.java, MappingApplyResult.java, LineEdit.java, Suggestion.java, SuggestionState.java}`
- Create: `engine-core-src/src/main/java/org/lifstools/mztab/validator/engine/mapping/{MappingApplier.java, CopyIndex.java, CvDeclarations.java, Provenance.java}`
- Test: `engine-core-src/src/test/java/org/lifstools/mztab/validator/engine/mapping/{MappingApplierTest, MappingApplierMergeTest, CopyIndexTest}.java`, and `engine-api/src/test/java/org/lifstools/mztab/validator/engine/api/LineEditWireTest.java`

**Interfaces:**
- Consumes: `MappingLoader`, `Evaluator`, `Functions`, `Render`, `Value.Ref`.
- Produces:
  - **The request:** `MappingApplyRequest(String mapping, String mappingVersion, Map<String,String> parameters, JsonNode instance, String text, boolean provenance)`.
  - **The result:** `MappingApplyResult(List<LineEdit> edits, List<Suggestion> suggestions, List<String> notes, String text)`.
    - `Suggestion(String key, String value, SuggestionState state)`, where `SuggestionState` is one of `NEW`, `REPLACES_EXISTING`, `UNCHANGED`, `CONFLICTS`.
  - **The edits:** `LineEdit` is a sealed interface, serialised with the property `op`, with these subtypes:
    - `InsertLine(long before, String text)`;
    - `SetCell(long line, int cell, String text)`;
    - `InsertColumn(String header, int position, String name, String fill)`.

    Names and fields are identical to the server's `EditOperation` (`EditOperation.java:27-48`). Cell 0 is the prefix, so an MTD value is cell 2.
  - **The entry point:** `MappingApplier.apply(MappingApplyRequest r, Set<String> engineVersions): MappingApplyResult`. A mapping whose `target` is not in `engineVersions` throws `IllegalArgumentException("mapping <id> targets <target>; this engine serves <versions>")`.

**Rules.**
- **Emission.** Metadata lines come in rule order. Lines whose value renders empty or null are skipped (`conv:265`).
- **References.** A `Value.Ref(element, name)` renders through `CopyIndex` as `element[n]`. The index rules:
  - `sample`, `assay` and `study_variable` keep the existing index of the element whose value (`sample[n]`, `assay[n]`, `study_variable[n]`) equals the name, else they get the next free index, in emission order;
  - `contact` uses `contact[n]-name`;
  - `ms_run` uses the run of the matched assay's `-ms_run_ref`, else the next free index;
  - every key and value reference to these elements is rendered from the same allocation, so the `-sample_ref`, `-ms_run_ref` and `-assay_refs` values agree with the keys.
- **CV declarations** (`CvDeclarations`) scan the rendered MTD values for `[label,` tokens, splitting on `|` exactly as `conv:266-267`. They declare the labels in the order of the mapping's `cvs`, then unknown labels sorted, as `(label, "unknown", "null")` plus the converter's note (`conv:268-273`), and only labels the copy has not declared. They come at the end of MTD.
- **Singletons.** `mzTab-version`, `mzTab-ID`, `title`, `description`, `quantification_method`, `small_molecule-quantification_unit`, `small_molecule_feature-quantification_unit` and `small_molecule-identification_reliability` are handled thus:
  - with a different existing value: `SetCell(line, 2, value)` with `REPLACES_EXISTING`;
  - with an equal value: `UNCHANGED`, and no edit.
- **Tables.**
  - A section the copy lacks is inserted after a blank line following MTD.
  - A section the copy has gets no edits. Its suggestions are `CONFLICTS`, with the note `"SML already exists; table imports into an existing section come with the summary-table import"`.
- **Insert positions.** A new MTD line goes after the last MTD line of the same element prefix, else after the last MTD line. Into an empty text, the edits produce the whole file. `text` in the result is the input with the edits applied.
- **Provenance.** As in the Global Constraints, when `provenance` is true. The webapp version comes from the engine's build properties.

- [ ] **Step 1: Write the failing tests**

```java
// LineEditWireTest: JSON of new LineEdit.InsertLine(3, "MTD\tx\ty") == {"op":"InsertLine","before":3,"text":"MTD\tx\ty"};
//   InsertColumn("SMH", 14, "abundance_assay[9]", "null") == {"op":"InsertColumn","header":"SMH","position":14,"name":"abundance_assay[9]","fill":"null"}
// CopyIndexTest: "MTD\tsample[1]\tP001\nMTD\tsample[3]\tP009\n" -> indexOf("sample","P001")==1, next("sample")==4
// MappingApplierTest (src/test/resources/mapping/tiny.yaml): empty text -> result.text() ==
//   "MTD\tmzTab-version\t2.0.0-M\nMTD\tsample[1]\tP001\nMTD\tsample[1]-species[1]\t[NCBITaxon, NCBITaxon:9606, Homo sapiens, ]\n"
//   + "MTD\tcv[1]-label\tNCBITaxon\nMTD\tcv[1]-full_name\tNCBI organismal classification\nMTD\tcv[1]-version\t2026-07-12\n"
//   + "MTD\tcv[1]-uri\thttp://purl.obolibrary.org/obo/ncbitaxon.owl\n"
//   a 2.0.0-M mapping applied with engineVersions {"2.1.0-M"} -> IllegalArgumentException
// MappingApplierMergeTest: copy has sample[1]=P009, assay[1]=P009 with -sample_ref sample[1], cv[1]-label=MS;
//   instance samples P001,P002 -> P001 is sample[2], P002 sample[3]; assay for P001 has -sample_ref sample[2];
//   study_variable[*]-assay_refs name assay[2]/assay[3]; no second MS declaration; sample[1] untouched;
//   copy with an SMH line -> every SML suggestion CONFLICTS and no edit touches SMH/SML lines;
//   copy with title "Old" -> SetCell(titleLine, 2, newTitle) with REPLACES_EXISTING
```

- [ ] **Step 2: Run the tests to verify they fail.** Run `./mvnw -pl engine-mztabm-2.0 -am test -Dtest='LineEditWireTest,CopyIndexTest,MappingApplier*Test' -Dsurefire.failIfNoSpecifiedTests=false`. Expected: a compilation failure.
- [ ] **Step 3: Implement.** Emit the whole line list with symbolic references first; allocate through `CopyIndex`; then diff against the copy to produce the edits.
- [ ] **Step 4: Run the tests to verify they pass.** Same command. Expected: all pass.
- [ ] **Step 5: Commit** with the message `engine: apply an output mapping to a copy's text as line edits`.

### Task 5: The 2.0 output mapping, the v3-workbook input mapping and the v3 equivalence test

**Files:**
- Create: `submission-vocabulary/mappings/output/mztab-2.0.0-M.yaml`, `submission-vocabulary/mappings/input/v3-workbook.yaml`
- Create the conformance example under `submission-vocabulary/examples/v3-example/`:
  - `instance.json`: the reader output of `templates/v3/LipidCompass_Submission_Template_v3_EXAMPLE.xlsx` at bb8dcbf;
  - `parameters.json`: `[{"study_id":"LCS-99999","dataset_id":"D1","dataset_index":"1"}, {"study_id":"LCS-99999","dataset_id":"D2","dataset_index":"2"}]`;
  - `expected/LCS-99999-01.mzTab` and `expected/LCS-99999-02.mzTab`: the bb8dcbf converter with `--study-id LCS-99999`;
  - `allowed-differences.yaml`: `[{key-prefix: "software[3]"}]`;
  - `README.md`: the refresh commands below, and that the goldens need Python ≥ 3.12 (compensated `sum`).

  Refresh commands (run in a lipidcompass-submissions checkout at the pinned commit):
  ```bash
  uv run --frozen python tools/read_submission_template.py templates/v3/LipidCompass_Submission_Template_v3_EXAMPLE.xlsx -o instance.json
  uv run --frozen python tools/convert_submission_template.py templates/v3/LipidCompass_Submission_Template_v3_EXAMPLE.xlsx -o expected --study-id LCS-99999
  ```
- Test: `engine-mztabm-2.0/src/test/java/org/lifstools/mztab/validator/engine/V3EquivalenceTwoZeroTest.java`, and `submission-vocabulary/src/test/java/org/lifstools/mztab/submission/InputMappingCoverageTest.java`

**Interfaces:**
- Consumes: `MappingApplier.apply` (Task 4), and the engine's in-process validation as `FactorsTwoZeroTest` uses it.
- Produces: the mapping ids `mztab-2.0.0-M` and `v3-workbook`, version `1.0.0`.

**The 2.0 output mapping** reproduces bb8dcbf `Converter.dataset` (`conv:182-289`) and `Converter.sml` (`conv:342-392`) rule for rule:
- **Contacts and publications:** e-mails validated with `normalise email`; DOIs via `normalise doi`, as `Converter.dois` (`conv:320-331`).
- **Constants:** `ms_run[n]-instrument_ref instrument[1]` for every run; `MEAN`, `VARIATION`, `RELIABILITY`; the `CVS` table as `cvs`.
- **Custom parameters:** `STUDY_CUSTOM` and `DATASET_CUSTOM` as literal lists iterated by `each`.
- **SML columns:** `opt_global_internal_standard` has `where` = "any Lipids row of the dataset has a non-empty `internal_standard`" (`conv:345`).
- **References:** written with `ref-to`.

**The v3-workbook input mapping** declares, for each sheet, its group and layout (from `conventions`) and each column's field id. The bb8dcbf reader executes it today, and `instance.json` is its output. The webapp's executor comes with N-2.

**When a converter rule cannot be expressed** with the forms and functions of Tasks 2–3:
1. Do not add a form.
2. Stop, and ask an adversarial reviewer to decide from the converter code and design §5.3 whether this is an allowed difference or a design change. Record a design change in the design before you implement it.

- [ ] **Step 1: Write the failing tests**

```java
// V3EquivalenceTwoZeroTest
@ParameterizedTest @ValueSource(strings = {"01", "02"})
void v3ExampleMatchesTheConverter(String n) {
    // apply mztab-2.0.0-M 1.0.0 to instance.json with parameters[n-1], empty text, provenance=true, engine versions of this engine
    // drop MTD lines whose key starts with an allowed key-prefix; normalise: MTD lines sorted by key, SMH/SML order kept
    assertThat(normalised(result.text())).isEqualTo(normalised(expected(n)));
}
@ParameterizedTest @ValueSource(strings = {"01", "02"})
void outputHasNoErrorsOrWarnings(String n) { /* validate result.text() in-process: zero messages at level Warn or Error */ }
// InputMappingCoverageTest: every field of every workbook=true group appears exactly once in v3-workbook.yaml
```

- [ ] **Step 2: Run the tests to verify they fail.** Run `./mvnw -pl engine-mztabm-2.0 -am test -Dtest='V3EquivalenceTwoZeroTest,InputMappingCoverageTest' -Dsurefire.failIfNoSpecifiedTests=false`. Expected: FAIL, because the mapping `mztab-2.0.0-M` is not found.
- [ ] **Step 3: Write both mappings** until the tests pass.
- [ ] **Step 4: Run the tests to verify they pass.** Same command. Expected: 5 tests pass.
- [ ] **Step 5: Commit** with the message `submission-vocabulary: mzTab-M 2.0 output and v3-workbook input mappings; v3 equivalence with the converter`.

### Task 6: The engine endpoint, the contract and the published files

**Files:**
- Modify: `engine-core-src/src/main/java/org/lifstools/mztab/validator/engine/EngineController.java`, adding `POST /engine/v1/mappings/apply`.
- Modify: `engine-api/src/main/resources/engine-openapi.json` (regenerated as `EngineOpenApiContractTest` says).
- Modify: `server/pom.xml`, adding the dependency `mztab-validator-submission-vocabulary`.
- Create: `server/src/main/java/org/lifstools/mztab/validator/webapp/vocabulary/SubmissionVocabularyController.java`
- Test: `engine-core-src/src/test/java/org/lifstools/mztab/validator/engine/EngineMappingEndpointTest.java`, and `server/src/test/java/org/lifstools/mztab/validator/webapp/vocabulary/SubmissionVocabularyControllerTest.java`

**Interfaces:**
- Consumes: `MappingApplier` (Task 4); the module's classpath files (Task 1).
- Produces:
  - **The engine endpoint.** `POST /engine/v1/mappings/apply`: a JSON `MappingApplyRequest` in, a JSON `MappingApplyResult` out.
    - The body is read through the controller's existing capped request reading (`EngineController.java:231-263`), not `@RequestBody`, so oversize gives the same status as the conversions endpoints.
    - It runs under `JobLimiter`; a full limiter answers 503 as `/validations` does.
    - An unknown mapping or version, or a target the engine does not serve, is 400 with `EngineErrorCode.INVALID_REQUEST`.
  - **The published files.** `GET /rest/v2/submission-vocabulary/{version}/{*path}`:
    - **Path.** Strip the leading `/` that Spring's `{*path}` captures. The path must then match `^(README\.md|LICENSE|(vocabulary|lists|mappings/(input|output)|schema|examples)/[A-Za-z0-9._/-]+)$` with no `..` segment, else 400.
    - **Version.** `version` must equal `Vocabulary.load().version()`, else 404.
    - **Responses.** `.yaml` is `application/yaml`, `.json` is `application/json`, `.md` is `text/markdown`, `LICENSE` is `text/plain`, and `.mzTab` is `text/tab-separated-values`, all UTF-8 with `Cache-Control: public, max-age=86400`.

- [ ] **Step 1: Write the failing tests**

```java
// EngineMappingEndpointTest (MockMvc, as EngineControllerTest; line-aware as WebappCopiesTest.line())
//   2.0 engine: POST v3 example D1 -> 200, $.text starts with "MTD\tmzTab-version\t2.0.0-M\nMTD\tmzTab-ID\tLCS-99999-01"
//   2.1 engine: the same request -> 400 INVALID_REQUEST
//   mapping "nope" -> 400 INVALID_REQUEST
// SubmissionVocabularyControllerTest
//   GET /rest/v2/submission-vocabulary/1.0.0/vocabulary/vocabulary.yaml -> 200, application/yaml, body contains "version: 1.0.0"
//   GET .../1.0.0/LICENSE -> 200 text/plain;  GET .../0.9.0/vocabulary/vocabulary.yaml -> 404
//   GET .../1.0.0/vocabulary/../../pom.xml -> 400;  GET .../1.0.0/src/x -> 400
```

- [ ] **Step 2: Run the tests to verify they fail.** Run `./mvnw -pl engine-mztabm-2.0,server -am test -Dtest='EngineMappingEndpointTest,SubmissionVocabularyControllerTest' -Dsurefire.failIfNoSpecifiedTests=false`. Expected: FAIL with 404.
- [ ] **Step 3: Implement both controllers.** Then run `./mvnw -pl engine-mztabm-2.0 -am test -Dtest=EngineOpenApiContractTest -Dopenapi.update=true -Dsurefire.failIfNoSpecifiedTests=false`.
- [ ] **Step 4: Run the tests to verify they pass.**
  - Run the Step 2 command.
  - Then run `./mvnw -Psnapshot-engines verify`, which includes the 2.1 engine (root `pom.xml:450-459`). Expected: all modules pass.
  - Then run `./mvnw -pl server dependency:tree -Dincludes=org.springframework:spring-core`. Expected: the Boot-managed version only.
- [ ] **Step 5: Commit** with the message `engine: /engine/v1/mappings/apply; server: publish the submission vocabulary read-only`.

### Task 7: lipidcompass-submissions builds v3 from the vocabulary (repository lipidcompass-submissions)

**Files:**
- Create: `config/submission-vocabulary/1.0.0/`, a vendored copy of the webapp's `submission-vocabulary/{vocabulary,lists,schema,README.md,LICENSE}`, with `SOURCE` holding the webapp commit hash.
- Create: `config/submission-template-v3/template.yaml`, the `template:` block moved verbatim from `fields.yaml:22-26`.
- Create: `tools/submission_spec.py` with:
  - `load_spec() -> dict`, which returns the exact dict `yaml.safe_load(fields.yaml)` returned before. It is assembled from `template.yaml`, the vocabulary's `workbook: true` groups, the `quantities.help`, and the `extra_lists` from the list files.
  - `load_options_raw() -> dict`, which returns what `options.yaml` plus `extra_lists` gave before.
- Create: `tools/sync_submission_vocabulary.py` (`--webapp <checkout>`; copies the files and writes `SOURCE`).
- Modify the callers, so they use `submission_spec`: `tools/build_submission_template.py` (`:72-73`, `:600`), `tools/migrate_submission_template.py:554`, `tests/unit/test_submission_template_v3.py:35`.
- Modify: `tools/build_submission_template.py` `write_schema`, so it adds `"vocabulary": "1.0.0"` to the schema's `template` block.
- Delete: `config/submission-template-v3/fields.yaml`, `config/submission-template-v3/options.yaml`. Keep `options.resolved.json`.
- Test: `tests/unit/test_vocabulary_build.py`

**Interfaces:**
- Consumes: the vocabulary files (Task 1).
- Produces: `templates/v3/submission-template-schema.json`, identical apart from `template.vocabulary`.

- [ ] **Step 1: Capture today's state.**
  1. Before any change, on `feat/vocabulary-from-webapp` at bb8dcbf, save the current spec: `python -c "import yaml,json;print(json.dumps(yaml.safe_load(open('config/submission-template-v3/fields.yaml'))))" > tests/fixtures/vocabulary-baseline/spec.json`.
  2. Save the current schema: copy `templates/v3/submission-template-schema.json` to `tests/fixtures/vocabulary-baseline/schema.json`.
  3. For each of the four `submissions/LCS-0012{1..4}/supplementary/LipidCompass_Submission_Template_v3_LCS-0012x.xlsx` and the EXAMPLE, save the reader output with `read.Reader(path, schema).read()` as `tests/fixtures/vocabulary-baseline/<stem>.json`.
  4. Commit with the message `test fixtures: v3 spec, schema and reader output before the vocabulary move`.
- [ ] **Step 2: Write the failing tests**

```python
V3_WORKBOOKS = sorted(ROOT.glob("submissions/*/supplementary/LipidCompass_Submission_Template_v3_*.xlsx")) + [EXAMPLE]

def test_four_filled_workbooks_and_the_example():
    assert len(V3_WORKBOOKS) == 5

def test_load_spec_equals_the_old_fields_yaml():
    assert submission_spec.load_spec() == json.loads((BASELINE / "spec.json").read_text())

def test_rebuilt_schema_equals_committed_apart_from_vocabulary(tmp_path):
    build.write_schema(submission_spec.load_spec(), build.resolve_options(refresh=False), tmp_path / "s.json")
    new = json.loads((tmp_path / "s.json").read_text())
    assert new["template"].pop("vocabulary") == "1.0.0"
    assert new == json.loads((BASELINE / "schema.json").read_text())

@pytest.mark.parametrize("xlsx", V3_WORKBOOKS, ids=lambda p: p.stem)
def test_reader_output_unchanged(xlsx):
    schema = json.loads(read.SCHEMA.read_text())
    assert to_json(read.Reader(xlsx, schema).read()) == json.loads((BASELINE / f"{xlsx.stem}.json").read_text())
```

- [ ] **Step 3: Run the tests to verify they fail.** Run `uv run --frozen pytest tests/unit/test_vocabulary_build.py`. Expected: an `ImportError` for `submission_spec`.
- [ ] **Step 4: Implement.** Run the sync (`uv run python tools/sync_submission_vocabulary.py --webapp ../jmzTab-m-webapp`), add `submission_spec.py` and `template.yaml`, switch the three callers, delete the two YAML files, and rebuild with `uv run python tools/build_submission_template.py`.
- [ ] **Step 5: Run the tests to verify they pass.** Run `uv run --frozen pytest tests/unit/test_vocabulary_build.py tests/unit/test_submission_template_v3.py tests/unit/test_migrate_submission_template.py`. Expected: all pass.

  Then check that the converter's output is unchanged:
  ```bash
  uv run --frozen python tools/convert_submission_template.py templates/v3/LipidCompass_Submission_Template_v3_EXAMPLE.xlsx -o "$TMPDIR/v3" --study-id LCS-99999
  diff -r "$TMPDIR/v3" ../jmzTab-m-webapp/submission-vocabulary/examples/v3-example/expected
  ```
  Expected: no differences.
- [ ] **Step 6: Commit** with the message `v3 template: build from the published submission vocabulary 1.0.0`.

---

## Self-review notes

- **Spec coverage:** design §6 N-1 is covered as follows:
  - vocabulary and lists: Task 1;
  - mapping language and schema: Task 2;
  - named functions: Task 3;
  - "apply output mapping" for 2.0: Tasks 4 and 6;
  - the 2.0 output and v3-workbook input mappings, and the conformance example: Task 5;
  - the v3 build from the published vocabulary: Task 7;
  - the read-only publication: Task 6.

  The "Done when" conditions are `V3EquivalenceTwoZeroTest`, `test_rebuilt_schema_equals_committed_apart_from_vocabulary` and `test_reader_output_unchanged`.
- **Plan review (2026-11-08).** An adversarial review found ten defects, all applied: the pin, the gaps in the expression forms, the aggregate and mass arithmetic, the edit cell and reference remapping, the callers in Task 7, the workbook count, the design amendments, the module wiring, and the number cases.
- **Deferred to N-2 by the design:** the XLSX executor, the New-file route, the skeleton, field rules, the vocabulary reference page (§7), and input mappings other than v3.
