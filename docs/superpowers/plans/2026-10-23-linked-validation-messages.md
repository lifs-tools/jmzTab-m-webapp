# Linked, Grouped and Fix-First Validation Messages Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** jmzTab-m records a location, a rule id and a plain-language fix on every `ValidationMessage` (2.1.0-SNAPSHOT and the new 1.0.8-SNAPSHOT line), and the validator SPA groups those messages, leads with the fix and opens each occurrence side by side in the document.

**Architecture:** Three parts, executed in order, in three repositories/branches. Part A adds optional `suggestion` / `rule_id` / `location` fields to jmzTab-m `master` and fills them where the problem is detected: the parser records section, row and MTD key per line; the CV-mapping handlers turn their JXPath pointers into mzTab keys and table cells. Part B creates the `1.0.x` line from the exact commit the validator runs today, ports the two 1.0.x fixes, proves by comparison that nothing else changed, and backports Part A as 1.0.8-SNAPSHOT. Part C bumps the validator to 1.0.8-SNAPSHOT, extends the CSV report and the metadata endpoint, and rebuilds the SPA's Messages tab as grouped, linked messages in a side-by-side layout.

**Tech Stack:** jmzTab-m: Java 25 (master) / Java 24 (1.0.x base), Maven, Jackson 2, commons-jxpath, JUnit 5. Validator: Spring Boot 3.5, springdoc, Angular 21 (zoneless, signals, OnPush), Angular CDK `BreakpointObserver`, Vitest, Playwright + axe.

**Spec:** `docs/superpowers/specs/2026-10-23-linked-validation-messages-design.md` (approved; amended while planning — §5.1 base of `1.0.x`, L10 grouping key).

## Global Constraints

- New `ValidationMessage` fields are **optional and additive**; a message without them serialises exactly as today (no `"suggestion"`, `"rule_id"` or `"location"` keys, not even `null`).
- JSON names are snake_case: `suggestion`, `rule_id`, `location`; `ValidationLocation` fields `section`, `row_index`, `column`, `metadata_key`, `metadata_group`, `element_path`; `section` values `mtd`, `smh`, `sml`, `sfh`, `smf`, `seh`, `sme`, `com`, `file`.
- The location never duplicates the line: the line stays only in `line_number`.
- `row_index` is the 0-based position in the model list of that table (`getSmallMoleculeSummary()` / `getSmallMoleculeFeature()` / `getSmallMoleculeEvidence()`), i.e. the n-th `SML`/`SMF`/`SME` line in file order. It equals the validator's `TableRow.index`.
- `metadata_group` is the MTD key's prefix before the first `[`, or `general` when the key has no `[` — the same rule as the validator's `SectionReader.groupOf`.
- Precision rule: a location field is set only when jmzTab-m knows it for certain; otherwise it stays absent.
- The existing `message` text of every type stays unchanged; the fix goes into `suggestion`.
- Fix texts are ASCII-only (`.properties` files are read as ISO-8859-1), one or two short imperative sentences, and never suggest terms the rule does not allow.
- jmzTab-m worktrees live outside the repository, next to it: `/Users/nilshoffmann/Projects/github.com/lifs-tools/jmzTab-m-worktrees/<name>`. Never touch the main jmzTab-m checkout (it is on the project lead's branch with uncommitted files).
- The `lipidcompass-submissions` / `lipidcompass-studies` files are private: never commit or quote their content or file names; reports on them contain counts only.
- Push, open PRs, or create remote branches only after asking the project lead. Stop points are marked **STOP — ask before pushing**.
- Validator: `level` filter semantics, `maxErrors`, existing endpoints and response shapes stay as they are; only the additive changes listed here.
- Commits end with `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.

## Rulings made while planning

1. **`1.0.x` base = `53242461`** (spec §5.1 correction). The `fix/1.0.7…` branch is a Java 11 / `javax` / Spring 4.3 code base that cannot replace the validator's dependency; `53242461` is the validator's current code and already `org.lifs-tools` / `org.lifstools.mztab2`. Cost if wrong: the two ported fixes would have to be re-applied to another base.
2. **Group key = level + type code + `rule_id`** (spec L10 amended). `ValidationMessage.code` holds `MZTabError.toString()`, e.g. `[Error-2009] line 5: "abundance_assay[3]" MUST be defined…\r\n`; the type code is its `[Level-NNNN]` prefix.
3. **Fix templates are filled by index**, not by order of appearance: `{0}`…`{9}` refer to the original's parameters by position, `{element}` and `{allowed}` are named extras. (The existing `MZTabError.fill` replaces placeholders in order of appearance regardless of their number; it stays as is for `message`.)
4. **Model classes are edited by hand** in the generator's style. Running the `generate-api-model` profile would rewrite every model class and drop hand-made changes such as master's `@JsonIdentityInfo`.
5. **Metadata links need the page that holds a key:** `GET /rest/v2/validations/{id}/metadata/{group}` gains an optional `key` query parameter that selects the page containing that key. No new endpoint.
6. **CSV `row` column is 1-based** (`row_index + 1`), matching the SPA's "row 2" labels; the existing `code` cell is now escaped like the other text cells (it contains commas, quotes and CRLF today and breaks the CSV).
7. **Validator branch:** `feat/linked-messages` from `feat/validator-spa`, in worktree `.claude/worktrees/feat-linked-messages`; rebase onto `master` once PR #77 merges.
8. **e2e uses a custom mapping without network:** one `MAY` rule on an element the fixture lacks (no OLS lookup), plus a parameter with an undefined CV label (checked before any rule, no OLS lookup).
9. Clients: rmzTab-m's `ValidationMessage$fromJSON` reads known fields only and pymzTab-m has no `ValidationMessage` model — the "clients choke on unknown fields" risk is retired by inspection; no task.

## Review Focus

1. **A file whose metadata `[n]` ids are not 1…n in list order** (e.g. `sample[3]` is the first sample): the semantic location must name `sample[3]-species[1]`, not `sample[1]-…` — the key comes from the element's `id`, never from the JXPath list position. Test in Task A5.
2. **Old persisted records** (no `suggestion`/`location`, `code` with the type prefix): the SPA groups them, shows `message` as the headline and renders no chips or links, without errors. Test in Task C3.
3. **A jump while the table has filters, a sort, a picked column set or a column window** that hides the target column: the table clears filters and sort, drops the picked set, moves the window so the column is visible, and opens page `floor(row_index / size)`. Test in Task C5.
4. **Messages from a file that could not be parsed** (`NOT_PARSED`, only the Messages tab): no document pane, no links, layout stays single-column even on wide screens. Test in Task C6.
5. **A metadata key beyond the first page of its group** (more than 50 entries, e.g. `sample[60]-species[1]`): the metadata pane opens the page that holds it. Test in Tasks C1 and C4.

---

# Part A — jmzTab-m `master` (2.1.0-SNAPSHOT)

**Worktree:** `/Users/nilshoffmann/Projects/github.com/lifs-tools/jmzTab-m-worktrees/linked-messages-2.1`, branch `feat/linked-validation-messages` from `origin/master` (`3b89150c` or later). Create it with `git -C /Users/nilshoffmann/Projects/github.com/lifs-tools/jmzTab-m fetch origin` then `git -C /Users/nilshoffmann/Projects/github.com/lifs-tools/jmzTab-m worktree add -b feat/linked-validation-messages /Users/nilshoffmann/Projects/github.com/lifs-tools/jmzTab-m-worktrees/linked-messages-2.1 origin/master`. All Part A paths are relative to it. Baseline before Task A1: `./mvnw -B -q install -DskipTests` succeeds.

Note: PR #206 (`fix/report-all-missing-abundance-columns`) touches `MZTabHeaderLineParser` / `SMHLineParser`; if it merges first, rebase Task A4 onto it.

### Task A1: `ValidationLocation` and the optional `ValidationMessage` fields

**Files:**
- Modify: `api/src/main/resources/mzTab_m_openapi.yml` (`ValidationMessage`, ~line 2471; add `ValidationLocation` after it)
- Create: `api/src/main/java/org/lifstools/mztab2/model/ValidationLocation.java`
- Modify: `api/src/main/java/org/lifstools/mztab2/model/ValidationMessage.java`
- Create: `api/src/main/java/uk/ac/ebi/pride/jmztab2/utils/errors/Locations.java`
- Test: `api/src/test/java/org/lifstools/mztab2/model/ValidationMessageJsonTest.java`, `api/src/test/java/uk/ac/ebi/pride/jmztab2/utils/errors/LocationsTest.java`

**Interfaces:**
- Produces:
  - `ValidationLocation` with `enum SectionEnum { MTD("mtd"), SMH("smh"), SML("sml"), SFH("sfh"), SMF("smf"), SEH("seh"), SME("sme"), COM("com"), FILE("file") }` (`@JsonValue getValue()`, `@JsonCreator fromValue(String)`), fields `section`, `Integer rowIndex`, `String column`, `String metadataKey`, `String metadataGroup`, `String elementPath`, each with fluent setter (`section(SectionEnum)`, `rowIndex(Integer)`, …), getter/setter, `equals`/`hashCode`/`toString`; JSON names as in Global Constraints; every property `@JsonInclude(JsonInclude.Include.NON_NULL)`.
  - `ValidationMessage.suggestion(String)`, `ruleId(String)`, `location(ValidationLocation)` + getters/setters; JSON `suggestion`, `rule_id`, `location`; `@JsonInclude(JsonInclude.Include.NON_NULL)`; included in `equals`, `hashCode`, `toString`, `@JsonPropertyOrder` after `line_number`.
  - `Locations` (final, static): `metadataKey(String key)` → section `MTD`, `metadataKey`, `metadataGroup = groupOf(key)`; `metadataGroup(String group)` → section `MTD`, group only; `tableCell(SectionEnum section, int rowIndex, @Nullable String column)`; `headerColumn(SectionEnum headerSection, String column)`; `section(SectionEnum)`; `groupOf(String key)`; `SectionEnum sectionOf(String linePrefix)` mapping `MTD|SMH|SML|SFH|SMF|SEH|SME|COM` (case-insensitive) to the enum, `null` otherwise.

- [ ] **Step 1: Write the failing tests**

```java
// ValidationMessageJsonTest — use the project's ObjectMapper setup (new ObjectMapper())
@Test void messageWithoutNewFieldsSerialisesAsBefore() {
    ValidationMessage m = new ValidationMessage().code("[Error-2009] line 5: x").category(CategoryEnum.LOGICAL)
        .messageType(MessageTypeEnum.ERROR).message("x").lineNumber(5L);
    String json = mapper.writeValueAsString(m);
    assertFalse(json.contains("suggestion")); assertFalse(json.contains("rule_id")); assertFalse(json.contains("location"));
}
@Test void oldJsonWithoutNewFieldsDeserialises() {
    ValidationMessage m = mapper.readValue("{\"code\":\"c\",\"category\":\"format\",\"message_type\":\"warn\",\"message\":\"m\",\"line_number\":3}", ValidationMessage.class);
    assertNull(m.getSuggestion()); assertNull(m.getRuleId()); assertNull(m.getLocation());
}
@Test void newFieldsRoundTripInSnakeCase() {
    ValidationMessage m = new ValidationMessage().code("c").category(CategoryEnum.CROSS_CHECK).message("m")
        .suggestion("Add a term.").ruleId("sample_species_may")
        .location(new ValidationLocation().section(SectionEnum.SML).rowIndex(1).column("best_id_confidence_measure")
            .elementPath("/smallMoleculeSummary[2]/@bestIdConfidenceMeasure"));
    String json = mapper.writeValueAsString(m);
    assertTrue(json.contains("\"rule_id\":\"sample_species_may\""));
    assertTrue(json.contains("\"section\":\"sml\"")); assertTrue(json.contains("\"row_index\":1"));
    assertTrue(json.contains("\"element_path\":"));
    assertFalse(json.contains("metadata_key"));
    assertEquals(m, mapper.readValue(json, ValidationMessage.class));
}
// LocationsTest
@Test void groupOfKey() {
    assertEquals("sample", Locations.groupOf("sample[3]-species[1]"));
    assertEquals("ms_run", Locations.groupOf("ms_run[1]-format"));
    assertEquals("general", Locations.groupOf("small_molecule-quantification_unit"));
    assertEquals("general", Locations.groupOf("mzTab-version"));
}
@Test void metadataKeyLocation() {
    ValidationLocation l = Locations.metadataKey("instrument[1]-name");
    assertEquals(SectionEnum.MTD, l.getSection()); assertEquals("instrument[1]-name", l.getMetadataKey());
    assertEquals("instrument", l.getMetadataGroup()); assertNull(l.getRowIndex());
}
@Test void sectionOfPrefix() { assertEquals(SectionEnum.SMF, Locations.sectionOf("SMF")); assertNull(Locations.sectionOf("XYZ")); }
```

- [ ] **Step 2: Run to verify they fail**

Run: `./mvnw -B -pl api test -Dtest='ValidationMessageJsonTest,LocationsTest'`
Expected: compilation FAIL (`ValidationLocation`, `Locations`, `suggestion(...)` missing).

- [ ] **Step 3: Add the schema to `mzTab_m_openapi.yml`** exactly as spec §5.2 (`suggestion`, `rule_id`, `location: $ref '#/components/schemas/ValidationLocation'`; `ValidationLocation` with `section` as `type: string` + enum, `row_index` `type: integer, format: int32, minimum: 0`, the four strings with the descriptions from §5.2). None of the new properties is `required`.

- [ ] **Step 4: Implement `ValidationLocation`, the `ValidationMessage` additions and `Locations`** in the generated-code style of `ValidationMessage.java` (same header, annotations, fluent setters, `toIndentedString`). Do not run the `generate-api-model` profile (Ruling 4).

- [ ] **Step 5: Run the tests**

Run: `./mvnw -B -pl api test`
Expected: PASS, including the existing api tests.

- [ ] **Step 6: Commit**

```bash
git add api/src/main/resources/mzTab_m_openapi.yml api/src/main/java/org/lifstools/mztab2/model/ValidationLocation.java api/src/main/java/org/lifstools/mztab2/model/ValidationMessage.java api/src/main/java/uk/ac/ebi/pride/jmztab2/utils/errors/Locations.java api/src/test/java/org/lifstools/mztab2/model/ValidationMessageJsonTest.java api/src/test/java/uk/ac/ebi/pride/jmztab2/utils/errors/LocationsTest.java
git commit -m "Add optional suggestion, rule_id and location to ValidationMessage"
```

### Task A2: Fix templates on `MZTabErrorType`, location and rule id on `MZTabError`, cross-check fix texts

**Files:**
- Modify: `api/src/main/java/uk/ac/ebi/pride/jmztab2/utils/errors/MZTabErrorType.java`
- Modify: `api/src/main/java/uk/ac/ebi/pride/jmztab2/utils/errors/MZTabError.java`
- Modify: `api/src/main/resources/conf1_1/mztab_crosscheck_error.properties`
- Test: `api/src/test/java/uk/ac/ebi/pride/jmztab2/utils/errors/MZTabErrorFixTest.java`

**Interfaces:**
- Consumes: Task A1 `ValidationLocation`, `ValidationMessage.suggestion/ruleId/location`.
- Produces:
  - `MZTabErrorType.getFix()` — read from `<prefix>fix_<keyword>` in `createMZTabError`, `null` when the property is missing (Task A3's test makes it mandatory).
  - `MZTabError`: keeps its constructor; stores the filled values; `MZTabError location(ValidationLocation)`, `ValidationLocation getLocation()`, `MZTabError column(String)`, `String getColumn()`, `MZTabError ruleId(String)`, `String getRuleId()`, `MZTabError fixContext(@Nullable String element, @Nullable String allowed)`, `String getSuggestion()` (filled fix or `null` when the type has none).
  - `static String MZTabError.fillFix(String template, List<String> values, @Nullable String element, @Nullable String allowed)`: replaces `{0}`–`{9}` with `values.get(n)` (empty string when `n >= values.size()`), `{element}` with `element` or `the element`, `{allowed}` with `allowed` or `the terms listed in the validation profile`.
  - `toValidationMessage()` additionally sets `suggestion(getSuggestion())`, `ruleId(getRuleId())`, `location(getLocation())`; `code` and `message` unchanged.

- [ ] **Step 1: Write the failing tests**

```java
@Test void fillFixUsesIndicesAndNamedExtras() {
    assertEquals("Keep only one of the terms b on el. Allowed: al.",
        MZTabError.fillFix("Keep only one of the terms {1} on {element}. Allowed: {allowed}.", List.of("a", "b"), "el", "al"));
    assertEquals("Change the name of p from \"a\" to \"b\".",
        MZTabError.fillFix("Change the {0} of {1} from \"{2}\" to \"{3}\".", List.of("name", "p", "a", "b"), null, null));
}
@Test void fillFixOutOfRangeAndMissingExtras() {
    assertEquals("x  y", MZTabError.fillFix("x {1} y", List.of("a"), null, null));
    assertEquals("Add a term to the element. Allowed: the terms listed in the validation profile.",
        MZTabError.fillFix("Add a term to {element}. Allowed: {allowed}.", List.of(), null, null));
}
@Test void toValidationMessageCarriesSuggestionRuleAndLocation() {
    ValidationLocation where = Locations.metadataKey("instrument[1]-name");
    ValidationMessage vm = new MZTabError(CrossCheckErrorType.CvTermNotAllowed, -1,
            "[MS, MS:1000040, m/z, ]", "/metadata/instrument/@name", "rule text")
        .ruleId("instrument_name_may").location(where)
        .fixContext("instrument[1]-name", "MS:1000031 (instrument model) or one of its child terms")
        .toValidationMessage();
    assertEquals("instrument_name_may", vm.getRuleId());
    assertSame(where, vm.getLocation());
    assertEquals("Replace the term [MS, MS:1000040, m/z, ] on instrument[1]-name with a term the validation profile allows: MS:1000031 (instrument model) or one of its child terms.",
        vm.getSuggestion());
    assertTrue(vm.getMessage().startsWith("The cv term \"[MS, MS:1000040, m/z, ]\" is not allowed")); // message unchanged
}
@Test void errorWithoutExtrasStillHasNoPlaceholders() {
    String s = new MZTabError(CrossCheckErrorType.CvUnused, -1, "UO").getSuggestion();
    assertFalse(s.contains("{"));
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./mvnw -B -pl api test -Dtest=MZTabErrorFixTest`
Expected: compilation FAIL.

- [ ] **Step 3: Implement** `getFix`, the `MZTabError` additions and `fillFix` (one regex `\{(\d|element|allowed)\}` with `Matcher.appendReplacement` + `Matcher.quoteReplacement`).

- [ ] **Step 4: Add `c_fix_<Name>` for all 16 cross-check keywords**, verbatim:

```properties
c_fix_Species=Use the NCBI Taxonomy name of the species in column "{0}" instead of "{1}".
c_fix_Database=Use the identifiers.org (MIRIAM) name of the database in column "{0}" instead of "{1}".
c_fix_Smiles=Correct the SMILES "{1}" in column "{0}" so that it describes a valid molecule structure.
c_fix_InchiKey=Replace "{1}" in column "{0}" with a standard InChIKey, e.g. BQJCRHHNABKAKU-KBQPJGBKSA-N.
c_fix_OptionalCVParamColumn=Write "{1}" in column "{0}" as a CV parameter: [CV label, accession, name, value].
c_fix_CvTermNotAllowed=Replace the term {0} on {element} with a term the validation profile allows: {allowed}.
c_fix_CvTermRequired=Add a term to {element}. Allowed: {allowed}.
c_fix_CvTermRecommended=Add a term to {element} (recommended). Allowed: {allowed}.
c_fix_CvTermOptional=Optionally, add a term to {element}. Allowed: {allowed}.
c_fix_CvTermNotInRule=Check the term {0} on {element}: the validation profile does not list it. Allowed: {allowed}.
c_fix_RulePointerObjectNull=Add a value for {element} ({2}). Allowed: {allowed}.
c_fix_CvTermXor=Keep only one of the terms {2} on {element}. Allowed, exactly one of: {allowed}.
c_fix_CvTermMalformed=Change the {0} of {1} from "{2}" to "{3}".
c_fix_CvUndefinedInMetadata=Add a cv[n] entry for "{0}" to the metadata (cv[n]-label, -full_name, -version, -uri), or correct the CV label of {1}.
c_fix_CvUnused=Remove the unused CV "{0}" from the metadata, or use it in a parameter.
c_fix_SemanticValidationException=Semantic validation stopped early: fix the problem described in the message and validate again.
```

- [ ] **Step 5: Run the api tests**

Run: `./mvnw -B -pl api test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add api/src/main/java/uk/ac/ebi/pride/jmztab2/utils/errors/MZTabErrorType.java api/src/main/java/uk/ac/ebi/pride/jmztab2/utils/errors/MZTabError.java api/src/main/resources/conf1_1/mztab_crosscheck_error.properties api/src/test/java/uk/ac/ebi/pride/jmztab2/utils/errors/MZTabErrorFixTest.java
git commit -m "Carry location, rule id and a suggested fix on MZTabError"
```

### Task A3: Fix texts for all format and logical message types

**Files:**
- Modify: `api/src/main/resources/conf1_1/mztab_format_error.properties` (38 types), `api/src/main/resources/conf1_1/mztab_logical_error.properties` (63 types)
- Test: `api/src/test/java/uk/ac/ebi/pride/jmztab2/utils/errors/FixTextCoverageTest.java`

**Interfaces:**
- Consumes: Task A2 `MZTabErrorType.getFix()`.
- Produces: `f_fix_<Name>` / `l_fix_<Name>` next to each `_cause_` line.

- [ ] **Step 1: Write the failing coverage test**

```java
static Stream<MZTabErrorType> allTypes() {
    return Stream.of(FormatErrorType.getValues(), LogicalErrorType.getValues(), CrossCheckErrorType.getValues()).flatMap(Arrays::stream);
}
@ParameterizedTest @MethodSource("allTypes")
void everyTypeHasAnAsciiFixThatOnlyUsesKnownPlaceholders(MZTabErrorType t) {
    String fix = t.getFix();
    assertNotNull(fix, "no _fix_ for code " + t.getCode()); assertFalse(fix.isBlank());
    assertTrue(StandardCharsets.US_ASCII.newEncoder().canEncode(fix), "non-ASCII fix for " + t.getCode());
    long params = Pattern.compile("\\{\\w\\}").matcher(t.getOriginal()).results().count();
    Pattern.compile("\\{([^}]*)\\}").matcher(fix).results().map(r -> r.group(1)).forEach(p -> assertTrue(
        p.equals("element") || p.equals("allowed") || (p.matches("\\d") && Integer.parseInt(p) < params),
        "placeholder {" + p + "} not available for code " + t.getCode()));
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -B -pl api test -Dtest=FixTextCoverageTest`
Expected: FAIL for every format/logical code (no `_fix_`).

- [ ] **Step 3: Write the 101 fix texts.** Rules: lead with the action verb; name the column/key/value with the original's parameters; say what a valid value looks like (e.g. "a positive integer", "`null`", "[CV label, accession, name, value]"); when no concrete fix exists, restate the requirement in plain words. Examples, verbatim:

```properties
l_fix_NotDefineInHeader=Add the column "{0}" to the header line of the "{1}" section, and a value (or null) for it in every row.
l_fix_NotNULL=Replace "null" in column "{0}" with a value; this column must not be null.
l_fix_NotDefineInMetadata=Define "{0}" in the metadata section.
f_fix_CountMatch=Make the row have exactly as many tab-separated values as the header has columns ({1}); it has {0}.
f_fix_MZTabVersion=Set "{0}" to a version of at least 2.0.0-M, e.g. 2.0.0-M.
```

- [ ] **Step 4: Run all api tests**

Run: `./mvnw -B -pl api test`
Expected: PASS (117 parameterised cases in `FixTextCoverageTest`).

- [ ] **Step 5: Commit**

```bash
git add api/src/main/resources/conf1_1/mztab_format_error.properties api/src/main/resources/conf1_1/mztab_logical_error.properties api/src/test/java/uk/ac/ebi/pride/jmztab2/utils/errors/FixTextCoverageTest.java
git commit -m "Add a suggested fix for every format and logical message type"
```

### Task A4: Structural locations from the parser

**Files:**
- Modify: `io/src/main/java/org/lifstools/mztab2/io/MzTabFileParser.java` (`check()` loop ~lines 283–470; `parse(...)` ~line 135)
- Modify (column at call sites): `io/src/main/java/uk/ac/ebi/pride/jmztab2/utils/parser/MZTabHeaderLineParser.java`, `SMHLineParser.java`, `SFHLineParser.java`, `SEHLineParser.java`, `MZTabDataLineParser.java`, `SMLLineParser.java`, `SMFLineParser.java`
- Modify (metadata group): `io/src/main/java/org/lifstools/mztab2/io/validators/{AssayValidator,MsRunValidator,StudyVariableValidator,StudyVariableGroupValidator,DatabaseValidator,SoftwareValidator,CvValidator,QuantificationMethodValidator,SmallMoleculeQuantificationUnitValidator,SmallMoleculeFeatureQuantificationUnitValidator,SmallMoleculeIdConfidenceMeasureValidator,MzTabVersionValidator,MzTabIdValidator}.java`
- Test: `io/src/test/java/org/lifstools/mztab2/io/StructuralLocationTest.java`, fixtures under `io/src/test/resources/locations/`

**Interfaces:**
- Consumes: Task A1 `Locations`, Task A2 `MZTabError.location/column/getLocation/getColumn`.
- Produces: every `MZTabError` in `MzTabFileParser.getErrorList()` with `lineNumber > 0` has a location with at least `section`; `row_index` for `SML`/`SMF`/`SME` lines; `metadata_key` + `metadata_group` for `MTD` lines; `column` where the call site set it.

- [ ] **Step 1: Write the fixtures and the failing tests.** Copy `test-utils/src/main/resources/metabolomics/lipidomics-example.mzTab` four times into `io/src/test/resources/locations/` and edit one thing in each:
  - `missing-abundance-column.mzTab`: delete the `abundance_assay[1]` column from the `SMH` line and the matching cell from every `SML` line.
  - `bad-row-value.mzTab`: add a second `SML` line (copy the first, new `SML_ID` 2) and set its `reliability` cell to `abc`.
  - `bad-mtd-value.mzTab`: change the value of `MTD	ms_run[1]-format` to `mzML` (not a parameter).
  - `bad-assay.mzTab`: delete the `MTD	assay[1]-ms_run_ref` line (→ `AssayValidator` reports `NotDefineInMetadata` for `assay[1]-ms_run_ref`, line −1).

```java
@Test void headerErrorCarriesHeaderSectionAndColumn() {
    MZTabError e = firstError("missing-abundance-column.mzTab", LogicalErrorType.NotDefineInHeader);
    assertEquals(SectionEnum.SMH, e.getLocation().getSection());
    assertEquals("abundance_assay[1]", e.getLocation().getColumn());
    assertNull(e.getLocation().getRowIndex());
}
@Test void dataRowErrorCarriesRowIndexAndColumn() {
    MZTabError e = firstErrorOnLineStartingWith("bad-row-value.mzTab", "SML\t2\t");
    assertEquals(SectionEnum.SML, e.getLocation().getSection());
    assertEquals(1, e.getLocation().getRowIndex()); // second SML line
    assertEquals("reliability", e.getLocation().getColumn());
}
@Test void metadataLineErrorCarriesKeyAndGroup() {
    MZTabError e = firstErrorOnLineStartingWith("bad-mtd-value.mzTab", "MTD\tms_run[1]-format");
    assertEquals(SectionEnum.MTD, e.getLocation().getSection());
    assertEquals("ms_run[1]-format", e.getLocation().getMetadataKey());
    assertEquals("ms_run", e.getLocation().getMetadataGroup());
}
@Test void everyLinedErrorHasASection() {
    for (String f : List.of("missing-abundance-column.mzTab", "bad-row-value.mzTab", "bad-mtd-value.mzTab")) {
        parse(f).getErrorList().stream().filter(e -> e.getLineNumber() > 0)
            .forEach(e -> assertNotNull(e.getLocation(), f + ": " + e));
    }
}
@Test void validatorErrorsNameTheirMetadataGroup() {
    MZTabError e = firstError("bad-assay.mzTab", LogicalErrorType.NotDefineInMetadata);
    assertEquals("assay", e.getLocation().getMetadataGroup());
    assertNull(e.getLocation().getMetadataKey()); // the key is missing from the file, so there is no line to link to
}
```

If `abc` in `reliability` raises no error on this line base, use the `charge` column with `abc` instead and assert `charge`. Helpers parse with `new MzTabFileParser(file).parse(System.out, MZTabErrorType.Level.Info, 500)`; `firstErrorOnLineStartingWith` finds the line number of that line in the file and filters errors by it.

- [ ] **Step 2: Run to verify they fail**

Run: `./mvnw -B -pl api,io -am install -DskipTests -q && ./mvnw -B -pl io test -Dtest=StructuralLocationTest`
Expected: FAIL (`getLocation()` is null).

- [ ] **Step 3: Record a location per line in `check()`.** Keep a `Map<Integer, ValidationLocation> lineLocations` (line → location) and per-table row counters; for each line: `COM` → `Locations.section(COM)`; `MTD` → `Locations.metadataKey(items[1].trim())` when the line has a key, else `section(MTD)`; `SMH`/`SFH`/`SEH` → `section(...)`; `SML`/`SMF`/`SME` → `tableCell(section, counter++, null)`. Record before calling the line parser, so errors thrown from it are covered.

- [ ] **Step 4: Attach locations after parsing.** In `parse(OutputStream, Level, int)`, in a `finally` that also runs after `MZTabErrorOverflowException`, for each error with `getLocation() == null` and `getLineNumber() > 0` and a recorded line: copy the recorded location and set `column` from `getColumn()` when present. Data-row `column` on a header line: use `Locations.headerColumn`.

- [ ] **Step 5: Set `.column(name)` at the call sites** in the header and data line parsers listed above where a column header name is in scope (the variable passed as the column parameter of the message). Do not restructure the parsers.

- [ ] **Step 6: Set `.location(Locations.metadataGroup(g))`** in the io validators: Assay → `assay`, MsRun → `ms_run`, StudyVariable → `study_variable`, StudyVariableGroup → `study_variable_group`, Database → `database`, Software → `software`, Cv → `cv`, SmallMoleculeIdConfidenceMeasure → `id_confidence_measure`, QuantificationMethod / SmallMoleculeQuantificationUnit / SmallMoleculeFeatureQuantificationUnit / MzTabVersion / MzTabId → `general`. Where a validator reports about one indexed element and has its id, use `Locations.metadataKey("<element>[<id>]")` instead only if that exact key is an MTD line the writer emits (e.g. `assay[3]`); otherwise keep the group.

- [ ] **Step 7: Run the io tests**

Run: `./mvnw -B -pl io test`
Expected: PASS, existing tests unchanged.

- [ ] **Step 8: Commit**

```bash
git add io/src
git commit -m "Record section, row, column and MTD key for parser messages"
```

### Task A5: Semantic locations, rule ids and allowed terms

**Files:**
- Create: `validation/src/main/java/org/lifstools/mztab2/validation/ModelLocations.java`
- Modify: `validation/src/main/java/org/lifstools/mztab2/cvmapping/CvMappingUtils.java`
- Modify: `validation/src/main/java/org/lifstools/mztab2/validation/handlers/{EmptyRuleHandler,AndValidationHandler,OrValidationHandler,XorValidationHandler,ExtraParametersValidationHandler,SharedParametersValidationHandler}.java`, `validation/src/main/java/org/lifstools/mztab2/validation/{CvMappingValidator,CvDefinitionValidationHandler}.java`
- Modify: `RELEASES.adoc`
- Test: `validation/src/test/java/org/lifstools/mztab2/validation/ModelLocationsTest.java`, `validation/src/test/java/org/lifstools/mztab2/validation/SemanticLocationTest.java`, `validation/src/test/java/org/lifstools/mztab2/validation/StubLookupService.java`

**Interfaces:**
- Consumes: Task A1 `Locations`, `ValidationLocation`; Task A2 `MZTabError.location/ruleId/fixContext`.
- Produces:
  - `ModelLocations.ofPointer(Pointer pointer): ValidationLocation` — exact element; `element_path = pointer.asPath()`; resolves bean ids through `JXPathContext.newContext(((NodePointer) pointer).getRootNode())`.
  - `ModelLocations.ofRulePath(String cvElementPath): ValidationLocation` — closest metadata group (or table section + column, no row).
  - `ModelLocations.describe(ValidationLocation location): String` — the `{element}` text.
  - `CvMappingUtils.allowedTermsText(CvMappingRule rule): String`.

Path → location mapping (`asPath()` segments, `@` stripped; `[i]` is the 1-based JXPath list position):

| Pointer path | `metadata_key` | `metadata_group` |
|---|---|---|
| `/metadata/msRun[i]/@format`, `@idFormat`, `@hashMethod` | `ms_run[<id>]-format` / `-id_format` / `-hash_method` | `ms_run` |
| `/metadata/msRun[i]/scanPolarity[j]`, `fragmentationMethod[j]` | `ms_run[<id>]-scan_polarity[j]` / `-fragmentation_method[j]` | `ms_run` |
| `/metadata/instrument[i]/@name`, `@source`, `@detector`; `analyzer[j]` | `instrument[<id>]-name` … `instrument[<id>]-analyzer[j]` | `instrument` |
| `/metadata/sample[i]/species[j]`, `tissue[j]`, `cellType[j]`, `disease[j]` | `sample[<id>]-species[j]` … `-cell_type[j]` | `sample` |
| `/metadata/studyVariable[i]/@averageFunction`, `@variationFunction` | `study_variable[<id>]-average_function` / `-variation_function` | `study_variable` |
| `/metadata/sampleProcessing[i]/sampleProcessing[j]` | `sample_processing[<id>]` | `sample_processing` |
| `/metadata/software[i]/@parameter` | `software[<id>]` | `software` |
| `/metadata/database[i]/@param` | `database[<id>]` | `database` |
| `/metadata/@quantificationMethod` | `quantification_method` | `general` |
| `/metadata/@smallMoleculeQuantificationUnit` | `small_molecule-quantification_unit` | `general` |
| `/metadata/@smallMoleculeFeatureQuantificationUnit` | `small_molecule_feature-quantification_unit` | `general` |
| `/metadata/@smallMoleculeIdentificationReliability` | `small_molecule-identification_reliability` | `general` |
| `/metadata/derivatizationAgent[j]`, `/metadata/idConfidenceMeasure[j]` | `derivatization_agent[<n>]` / `id_confidence_measure[<n>]`, `<n>` numbered as `MetadataSerializer.serializeParameterListWithMetadataElement` numbers them | `derivatization_agent` / `id_confidence_measure` |
| `/smallMoleculeSummary[i]/@bestIdConfidenceMeasure` | — (`section sml`, `row_index i-1`, `column best_id_confidence_measure`) | — |
| `/smallMoleculeEvidence[i]/@derivatizedForm`, `@msLevel`, `@identificationMethod` | — (`section sme`, `row_index i-1`, `column derivatized_form` / `ms_level` / `identification_method`) | — |

General rule behind the table: element name = snake_case of the first segment (`msRun` → `ms_run`); sub-property = `-` + snake_case, with `[j]` kept for list properties; the three self-named parameter properties (`software.parameter`, `database.param`, `sampleProcessing.sampleProcessing`) add no suffix; `<id>` = the element bean's `getId()`. Check every produced element/property name against `MetadataElement` / `MetadataProperty.findProperty`; if the check fails or the id is `null`, leave `metadata_key` empty and keep `metadata_group` + `element_path` (precision rule). Table columns = snake_case of the property. `ofRulePath`: group = snake_case element name when `Metadata`'s getter for it returns a `List`, else `general` (the three `small_molecule…` scalars and `quantificationMethod` → `general`); for `/smallMolecule{Summary,Feature,Evidence}/@x` → section `sml`/`smf`/`sme` + column, no row.

`describe`: MTD key → the key; table → `<column> in <table name> row <row_index+1>` with table names `small molecule summary` / `small molecule feature` / `small molecule evidence`; group from a rule path → the key pattern with `[n]`, e.g. `sample[n]-species`, or the scalar key, e.g. `quantification_method`.

`allowedTermsText`: each term as `<accession> (<name>)`, plus ` or one of its child terms` when `allowChildren && useTerm`, prefixed `a child term of ` when `allowChildren && !useTerm`; joined with ` or ` (OR, XOR) or ` and ` (AND). Example: `MS:1000031 (instrument model) or one of its child terms`.

- [ ] **Step 1: Write `StubLookupService`** (test): `extends CvParameterLookupService`, constructor `StubLookupService(Map<String, Set<String>> childrenByParent)`, overrides `isChildOfOrSame(Parameter parent, Parameter child)` returning `IDENTICAL` for equal accessions, `CHILD_OF` when the child accession is in the parent's set, else `NOT_RELATED` — no network.

- [ ] **Step 2: Write the failing tests**

```java
// ModelLocationsTest — build MzTab via MzTabValidatorTest.createTestFile() plus elements below
@Test void sampleKeyUsesTheElementIdNotTheListPosition() {
    // first sample in the list has id 3
    mzTab.getMetadata().setSample(new ArrayList<>(List.of(
        new Sample().id(3).name("s3").addSpeciesItem(param("NEWT", "NEWT:9606", "Homo sapiens")))));
    Pair<Pointer, Parameter> p = select(mzTab, "/metadata/sample/@species").get(0);
    ValidationLocation l = ModelLocations.ofPointer(p.getKey());
    assertEquals("sample[3]-species[1]", l.getMetadataKey());
    assertEquals("sample", l.getMetadataGroup());
    assertEquals(SectionEnum.MTD, l.getSection());
    assertEquals(p.getKey().asPath(), l.getElementPath());
}
@Test void msRunScalarProperty() { assertEquals("ms_run[1]-format", locate("/metadata/msRun/@format").getMetadataKey()); }
@Test void selfNamedParameterLists() {
    assertEquals("software[1]", locate("/metadata/software/@parameter").getMetadataKey());
    assertEquals("database[1]", locate("/metadata/database/@param").getMetadataKey());
}
@Test void scalarMetadata() {
    ValidationLocation l = locate("/metadata/@smallMoleculeQuantificationUnit");
    assertEquals("small_molecule-quantification_unit", l.getMetadataKey()); assertEquals("general", l.getMetadataGroup());
}
@Test void tableCell() {
    // two SML rows, second has a bestIdConfidenceMeasure parameter
    ValidationLocation l = locate("/smallMoleculeSummary/@bestIdConfidenceMeasure", 1);
    assertEquals(SectionEnum.SML, l.getSection()); assertEquals(1, l.getRowIndex());
    assertEquals("best_id_confidence_measure", l.getColumn()); assertNull(l.getMetadataKey());
}
@Test void rulePathGroups() {
    assertEquals("sample", ModelLocations.ofRulePath("/metadata/sample/@species").getMetadataGroup());
    assertEquals("general", ModelLocations.ofRulePath("/metadata/@quantificationMethod").getMetadataGroup());
    assertEquals("id_confidence_measure", ModelLocations.ofRulePath("/metadata/@idConfidenceMeasure").getMetadataGroup());
    ValidationLocation t = ModelLocations.ofRulePath("/smallMoleculeEvidence/@msLevel");
    assertEquals(SectionEnum.SME, t.getSection()); assertEquals("ms_level", t.getColumn()); assertNull(t.getRowIndex());
}
@Test void describeTexts() {
    assertEquals("sample[n]-species", ModelLocations.describe(ModelLocations.ofRulePath("/metadata/sample/@species")));
    assertEquals("best_id_confidence_measure in small molecule summary row 2",
        ModelLocations.describe(Locations.tableCell(SectionEnum.SML, 1, "best_id_confidence_measure")));
}

// SemanticLocationTest — run CvMappingValidator.of(mapping, new StubLookupService(...), true).validate(mzTab)
@Test void notAllowedTermIsReportedPerElementWithExactKey() {
    // instrument rule: MS:1000031 allowChildren, OR; three instruments ids 1,2,3; instruments 1 and 3 use MS:1000040 (NOT_RELATED)
    List<ValidationMessage> ms = only(CrossCheckErrorType.CvTermNotAllowed);
    assertEquals(List.of("instrument[1]-name", "instrument[3]-name"), keys(ms));
    ms.forEach(m -> assertEquals("instrument_name_may", m.getRuleId()));
    assertTrue(ms.get(0).getSuggestion().contains("MS:1000031 (instrument model) or one of its child terms"));
}
@Test void missingElementPointsAtTheGroup() {
    ValidationMessage m = only(CrossCheckErrorType.RulePointerObjectNullOptional).get(0); // sample_species_may, samples without species
    assertEquals("sample", m.getLocation().getMetadataGroup()); assertNull(m.getLocation().getMetadataKey());
    assertEquals("sample_species_may", m.getRuleId());
    assertTrue(m.getSuggestion().startsWith("Add a value for sample[n]-species (optional)."));
}
@Test void undefinedCvPointsAtTheParameter() {
    // instrument[2]-name uses cv label "XMS", not defined
    ValidationMessage m = only(CrossCheckErrorType.CvUndefinedInMetadata).get(0);
    assertEquals("instrument[2]-name", m.getLocation().getMetadataKey()); assertNull(m.getRuleId());
}
@Test void unusedCvPointsAtItsLabelKey() {
    ValidationMessage m = only(CrossCheckErrorType.CvUnused).get(0); // cv[4] "UO" unused
    assertEquals("cv[4]-label", m.getLocation().getMetadataKey());
}
@Test void everySemanticMessageFromARuleHasItsRuleId() { /* all messages except CvDefinition ones and SemanticValidationException carry rule_id */ }
```

- [ ] **Step 3: Run to verify they fail**

Run: `./mvnw -B -pl api,io -am install -DskipTests -q && ./mvnw -B -pl validation test -Dtest='ModelLocationsTest,SemanticLocationTest'`
Expected: FAIL.

- [ ] **Step 4: Implement `ModelLocations` and `CvMappingUtils.allowedTermsText`.**

- [ ] **Step 5: Use them in the handlers.** Every message created inside `CvMappingValidator.handleRule` gets `.ruleId(rule.getId())` and `.fixContext(ModelLocations.describe(loc), CvMappingUtils.allowedTermsText(rule))` with:
  - `EmptyRuleHandler`, And/Or/Xor "term missing" (`CvTermRequired/Recommended/Optional`) and `CvTermXor`: `loc = ModelLocations.ofRulePath(rule.getCvElementPath())`.
  - `ExtraParametersValidationHandler`: split — for each pair in `result.getFilteredSelection()` whose upper-cased accession is in the extra set, one message with `loc = ModelLocations.ofPointer(pair.getKey())` and that pair's parameter as `{0}`.
  - `SharedParametersValidationHandler` (`CvTermMalformed`): `loc = ofPointer(p.getKey())`.
  - `CvDefinitionValidationHandler`: `CvUndefinedInMetadata` → `ofPointer(t.getKey())`, no rule id; `CvUnused` → `Locations.metadataKey("cv[" + id + "]-label")` using the `CV` with that label, no rule id.
  - `SemanticValidationException`: rule id only.

- [ ] **Step 6: Add the release note** to `RELEASES.adoc` under the next 2.1.0 entry: "Validation messages may carry `suggestion`, `rule_id` and `location` (optional, additive). Semantic messages about terms that are not allowed are now reported once per affected element, so their count can rise."

- [ ] **Step 7: Run the whole build**

Run: `./mvnw -B verify`
Expected: PASS. (Some existing validation tests call OLS; with no network, run `./mvnw -B verify -pl api,io` plus the new validation tests and note the skipped ones.)

- [ ] **Step 8: Commit**

```bash
git add validation/src RELEASES.adoc
git commit -m "Report exact elements, rule ids and allowed terms for semantic messages"
```

- [ ] **Step 9: STOP — ask before pushing.** On approval: `git push -u origin feat/linked-validation-messages` and open a PR against `master` titled "Linked, fix-first validation messages (optional ValidationMessage fields)".

---

# Part B — jmzTab-m `1.0.x` (1.0.8-SNAPSHOT)

**Worktree:** `/Users/nilshoffmann/Projects/github.com/lifs-tools/jmzTab-m-worktrees/1.0.x`, branch `release/1.0.8` created from `53242461`: `git -C /Users/nilshoffmann/Projects/github.com/lifs-tools/jmzTab-m worktree add -b release/1.0.8 /Users/nilshoffmann/Projects/github.com/lifs-tools/jmzTab-m-worktrees/1.0.x 53242461`. The PR target `1.0.x` is created on origin at `53242461` in Task B4 (the workflow at `53242461` does not run on `1.0.x`, so creating it publishes nothing).

### Task B1: Version 1.0.8-SNAPSHOT, the two 1.0.x fixes, CI publishing from `1.0.x`

**Files:**
- Modify: every `pom.xml` (`1.0.7-SNAPSHOT` → `1.0.8-SNAPSHOT`, parent and module versions)
- Port: `910bc44b` (`io/…/MZTabHeaderLineParser.java`, `io/…/SMHLineParser.java`, `io/src/test/…/HeaderLineParserTest.java`), `b7c36c3f` (`cli/…/MZTabCommandLine.java`, `cli/src/test/…/MZTabCommandLineTest.java`) — both written against `de.isas.mztab2`; use `org.lifstools.mztab2`
- Modify: `.github/workflows/maven.yml` (`on.push.branches` gains `1.0.x`)

**Interfaces:**
- Produces: artifacts `org.lifs-tools:jmztabm-*:1.0.8-SNAPSHOT`, packages `org.lifstools.mztab2`, unchanged public API.

- [ ] **Step 1: Bump the version** with `./mvnw -B versions:set -DnewVersion=1.0.8-SNAPSHOT -DgenerateBackupPoms=false`. Verify: `grep -rn "1.0.7-SNAPSHOT" --include=pom.xml .` prints nothing.
- [ ] **Step 2: Port `910bc44b` and `b7c36c3f`** with `git cherry-pick -x`; resolve the package paths (`de/isas/mztab2` → `org/lifstools/mztab2`). If `53242461` already contains part of a change (910bc44b says the identification-reliability part is "same change as on master"), keep `53242461`'s version and port only the rest. The ported tests come with them.
- [ ] **Step 3: Add `1.0.x` to the push branches** in `.github/workflows/maven.yml`. The `dev-latest` job stays master-only (its `if` already says so).
- [ ] **Step 4: Check names:** `grep -rn "de\.isas" --include=*.java --include=*.xml --include=*.yml --include=*.properties .` prints only licence headers or nothing.
- [ ] **Step 5: Build and test**

Run: `./mvnw -B verify`
Expected: PASS including the ported `HeaderLineParserTest` and `MZTabCommandLineTest` cases.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "1.0.8-SNAPSHOT: port the 1.0.x header and JSON fixes, publish from 1.0.x"
```

### Task B2: Behavioural comparison with the validator's 1.0.7-SNAPSHOT

**Files:**
- Create (not committed): `/private/tmp/jmztab-compare/Compare.java`, outputs under `/private/tmp/jmztab-compare/out/`
- Create: `docs/1.0.8-comparison.md` (committed; counts and fixture names only)

**Interfaces:**
- Consumes: the 1.0.7 build (`git worktree add /Users/nilshoffmann/Projects/github.com/lifs-tools/jmzTab-m-worktrees/1.0.7-build 53242461`, `./mvnw -B -q package -DskipTests` there) and this branch's build.

- [ ] **Step 1: Write `Compare.java`** (single-file program, run with `java -cp "<cli jar>:<cli/target/lib>/*" Compare.java <listfile> <out.tsv>`): for each path in `<listfile>`, `new MzTabFileParser(file).parse(OutputStream.nullOutputStream(), MZTabErrorType.Level.Info, 10000)` and write one line per error: `<file-id>\t<level>\t<code>\t<line>`, sorted; `<file-id>` is the path for fixtures and `sha1(path)` for the private corpus. Catch exceptions per file and write `<file-id>\tEXCEPTION\t<class>\t-1`.
- [ ] **Step 2: Build the file lists**: fixtures = `find test-utils/src/main/resources -iname '*.mztab'`; corpus = `find /Users/nilshoffmann/Projects/github.com/lifs-tools/lipidcompass-submissions /Users/nilshoffmann/Projects/github.com/lifs-tools/lipidcompass-studies -name '*.mzTab'`.
- [ ] **Step 3: Run both builds over both lists** and `diff` the outputs.
- [ ] **Step 4: Explain every difference.** Allowed: additional `2009` (NotDefineInHeader) errors on `SMH`/`SFH` lines (910bc44b reports all missing abundance columns) and validations that previously stopped after Info-2041. Anything else is a regression: fix it on this branch with a test, rerun.
- [ ] **Step 5: Write `docs/1.0.8-comparison.md`**: the commit range compared, number of fixture and corpus files, per-code count differences (corpus: counts only), the explanation per difference, and "Not ported from master after 53242461: #187 ColumnPosition (API change, reason for 2.1.0); 09b055d6 @JsonIdentityInfo on metadata beans (changes the JSON shape) — candidate for a decision by the project lead".
- [ ] **Step 6: Commit**

```bash
git add docs/1.0.8-comparison.md
git commit -m "Document the 1.0.7-SNAPSHOT to 1.0.8-SNAPSHOT behaviour comparison"
```

### Task B3: Backport Part A

**Files:** the files of Tasks A1–A5, as they exist on this line.

**Interfaces:**
- Consumes: Part A commits on `feat/linked-validation-messages` (A1…A5).
- Produces: the same public API and behaviour as Part A on 1.0.8-SNAPSHOT.

- [ ] **Step 1: Cherry-pick A1–A5** in order (`git cherry-pick -x <sha>`). Conflicts are expected in the header parsers (no #187 `ColumnPosition` here): keep this line's parser code and re-apply only the `.column(...)` calls and the location recording.
- [ ] **Step 2: Keep only fix texts for types that exist here** (`FixTextCoverageTest` enumerates this line's types; delete fix lines for keywords this line does not have).
- [ ] **Step 3: Run the build**

Run: `./mvnw -B verify`
Expected: PASS including all Part A tests.

- [ ] **Step 4: Rerun the comparison (Task B2 Steps 3–4).** Expected: identical structural results to Task B2 (the backport adds fields, not messages). Update `docs/1.0.8-comparison.md` with "rerun after backport: no change" or the explained difference.
- [ ] **Step 5: Install locally for Part C**: `./mvnw -B -q install -DskipTests`.
- [ ] **Step 6: Commit** the conflict resolutions if any are left uncommitted, message `Backport linked, fix-first validation messages to 1.0.x`.

### Task B4: Publish

- [ ] **Step 1: STOP — ask before pushing.** On approval: `git -C /Users/nilshoffmann/Projects/github.com/lifs-tools/jmzTab-m push origin 53242461:refs/heads/1.0.x`, `git push -u origin release/1.0.8`, open a PR `release/1.0.8` → `1.0.x` with `docs/1.0.8-comparison.md` linked in the body and the fix texts called out for the project lead's review (spec §5.4).
- [ ] **Step 2:** After the merge, confirm the CI run on `1.0.x` published `org.lifs-tools:jmztabm-api:1.0.8-SNAPSHOT` to `https://m.lifs-tools.org/` (`lifs-libs-snapshot-local`).

---

# Part C — Validator (jmzTab-m-webapp)

**Worktree:** `.claude/worktrees/feat-linked-messages` of jmzTab-m-webapp, branch `feat/linked-messages` from `feat/validator-spa`: `git -C /Users/nilshoffmann/Projects/github.com/lifs-tools/jmzTab-m-webapp worktree add -b feat/linked-messages /Users/nilshoffmann/Projects/github.com/lifs-tools/jmzTab-m-webapp/.claude/worktrees/feat-linked-messages feat/validator-spa`, then copy this plan and the amended spec from `docs/linked-validation-messages` (`git checkout docs/linked-validation-messages -- docs/superpowers`) and commit them first. Until Task B4 publishes the snapshot, the server builds against the locally installed 1.0.8-SNAPSHOT (Task B3 Step 5); CI needs the published one. Baseline: `./mvnw -B verify` is green before Task C1.

### Task C1: Server — jmzTab-m 1.0.8-SNAPSHOT, CSV columns, metadata `key`, regenerated API

**Files:**
- Modify: `pom.xml:72` (`jmztabm.version` → `1.0.8-SNAPSHOT`)
- Modify: `server/src/main/java/org/lifstools/mztab2/server/api/validation/ValidationsApiController.java` (`csv`, `getMetadataPage`)
- Modify: `server/src/main/java/org/lifstools/mztab/validator/webapp/sections/SectionReader.java` (`metadata`)
- Modify: `frontend/openapi/mztab-m-api.json`, regenerated `frontend/projects/webapp/src/app/api/**`
- Test: `server/src/test/java/org/lifstools/mztab2/server/api/validation/ValidationsApiControllerTest.java`, `server/src/test/java/org/lifstools/mztab/validator/webapp/sections/SectionReaderTest.java`

**Interfaces:**
- Produces:
  - CSV header `level,line,category,code,message,suggestion,rule_id,section,row,column,metadata_key,metadata_group`; `row` = `row_index + 1`; all text cells, now including `code`, through `field(...)`.
  - `SectionReader.metadata(UUID id, String group, int page, int size, @Nullable String key)`: when `key` is in the group, the returned page is `indexOf(key) / size` (the `page` argument is ignored); otherwise as today. The 4-argument overload delegates with `null`.
  - `GET /rest/v2/validations/{id}/metadata/{group}?key=...` (optional).
  - TS: `ValidationMessage` gains `suggestion?`, `rule_id?`, `location?: ValidationLocation`; `getMetadataPage` params gain `key?`.

- [ ] **Step 1: Write the failing tests**

```java
@Test void csvHasLocationColumnsAndEscapesCode() {
    ValidationMessage m = new ValidationMessage().code("[Error-2009] line 5: \"x\", y\r\n").category(CategoryEnum.LOGICAL)
        .messageType(MessageTypeEnum.ERROR).message("m").lineNumber(5L).suggestion("Add the column.").ruleId(null)
        .location(new ValidationLocation().section(SectionEnum.SML).rowIndex(1).column("reliability"));
    String[] lines = ValidationsApiController.csv(List.of(m)).split("\r\n");
    assertEquals("level,line,category,code,message,suggestion,rule_id,section,row,column,metadata_key,metadata_group", lines[0]);
    assertEquals("ERROR,5,LOGICAL,\"[Error-2009] line 5: \"\"x\"\", y\",m,Add the column.,,sml,2,reliability,,", lines[1]);
}
@Test void csvWithoutLocationLeavesColumnsEmpty() { /* old message -> 7 trailing empty cells */ }
// SectionReaderTest
@Test void metadataKeySelectsThePageThatHoldsIt() {
    // session with 120 entries sample[1]..sample[120] (key "sample[i]-description")
    MetadataPage p = reader.metadata(id, "sample", 0, 50, "sample[60]-description").orElseThrow();
    assertEquals(1, p.page()); assertTrue(p.entries().stream().anyMatch(e -> e.key().equals("sample[60]-description")));
}
@Test void unknownKeyFallsBackToThePageArgument() { assertEquals(2, reader.metadata(id, "sample", 2, 50, "nope").orElseThrow().page()); }
```

Plus a MockMvc test `metadataPageAcceptsKey` asserting `jsonPath("$.page").value(1)` for `?key=sample[60]-description&size=50`, and in `ValidationRecordStoreTest` a test `keepsSuggestionRuleIdAndLocation`: save a record whose message has `suggestion`, `rule_id` and a `location` with all six fields, load it, assert `equals` on the message.

- [ ] **Step 2: Run to verify they fail**

Run: `./mvnw -B -pl server -am test -Dfrontend.skip=true -Dtest='ValidationsApiControllerTest,SectionReaderTest'`
Expected: FAIL.

- [ ] **Step 3: Bump `jmztabm.version`, implement the CSV change and the `key` parameter.**
- [ ] **Step 4: Regenerate the OpenAPI document and the client**

Run: `./mvnw -B -pl server -am test -Dfrontend.skip=true -Dtest=OpenApiSpecExportTest -Dopenapi.update=true` then `npm --prefix frontend run generate:api`
Expected: `frontend/openapi/mztab-m-api.json` contains `ValidationLocation`, `rule_id`, `suggestion` and the `key` parameter; `frontend/projects/webapp/src/app/api/models/validation-location.ts` exists.

- [ ] **Step 5: Run the full build**

Run: `./mvnw -B verify`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add pom.xml server/src frontend/openapi frontend/projects/webapp/src/app/api
git commit -m "Validator: jmzTab-m 1.0.8-SNAPSHOT, location columns in the CSV report, metadata page by key"
```

### Task C2: SPA — grouping and link targets

**Files:**
- Create: `frontend/projects/webapp/src/app/pages/result/message-groups.ts`
- Test: `frontend/projects/webapp/src/app/pages/result/message-groups.spec.ts`

**Interfaces:**
- Consumes: Task C1 TS `ValidationMessage` with `suggestion`, `rule_id`, `location`.
- Produces:

```ts
export type Level = 'error' | 'warn' | 'info';
export type DocTab = 'metadata' | 'summary' | 'features' | 'evidence';
export interface DocTarget { tab: DocTab; group?: string; key?: string; rowIndex?: number; column?: string; header?: boolean; }
export interface MessageGroup {
  key: string; level: Level; typeCode: string | null; ruleId: string | null;
  category: ValidationMessage['category'];
  headline: string;            // suggestion of the first message, else its message
  diagnosis: string | null;    // message of the first message when it has a suggestion
  messages: ValidationMessage[];
}
export function typeCode(m: ValidationMessage): string | null;      // "Error-2009" from "[Error-2009] …"
export function groupKey(m: ValidationMessage): string;             // `${level}|${typeCode ?? 'msg:' + message}|${rule_id ?? ''}`
export function groupMessages(ms: readonly ValidationMessage[]): MessageGroup[]; // error, warn, info; then first appearance
export function targetOf(m: ValidationMessage): DocTarget | null;
export function occurrenceLabel(m: ValidationMessage): string;
```

`targetOf`: `mtd` with key → `{tab:'metadata', group: metadata_group ?? groupOf(key), key}`; `mtd` with group only → `{tab:'metadata', group}`; `sml|smf|sme` with `row_index` → `{tab, rowIndex, column}`; `smh|sfh|seh` with `column`, or `sml|smf|sme` with `column` and no row → `{tab, column, header:true}`; anything else → `null`. Tabs: `mtd`→metadata, `sml|smh`→summary, `smf|sfh`→features, `sme|seh`→evidence.

`occurrenceLabel`: key → the key; group only → `metadata group: <group>`; table row → `<SML|SMF|SME> row <rowIndex+1>` + ` · <column>` when present; header → `<SMH|SFH|SEH> · <column>`; no target but `line_number > 0` → `line <n>`; else `—`.

- [ ] **Step 1: Write the failing tests** (`msg(over)` builds a `ValidationMessage` with defaults like `messages-tab.component.spec.ts:7`)

```ts
it('extracts the type code from jmzTab-m codes', () => {
  expect(typeCode(msg({ code: '[Error-2009] line 5: x\r\n' }))).toBe('Error-2009');
  expect(typeCode(msg({ code: '' }))).toBeNull();
});
it('groups by level, type code and rule id, errors first', () => {
  const g = groupMessages([
    msg({ message_type: 'info', code: '[Info-3010] line -1: a', rule_id: 'sample_species_may' }),
    msg({ message_type: 'error', code: '[Error-2009] line 5: b' }),
    msg({ message_type: 'info', code: '[Info-3010] line -1: c', rule_id: 'sample_species_may' }),
    msg({ message_type: 'info', code: '[Info-3010] line -1: d', rule_id: 'sample_tissue_may' }),
  ]);
  expect(g.map((x) => x.messages.length)).toEqual([1, 2, 1]);
  expect(g[0].level).toBe('error');
});
it('uses the suggestion as headline and keeps the message as diagnosis', () => {
  const [g] = groupMessages([msg({ suggestion: 'Add the column.', message: 'x MUST be defined' })]);
  expect(g.headline).toBe('Add the column.'); expect(g.diagnosis).toBe('x MUST be defined');
});
it('falls back to the message for old records', () => {
  const [g] = groupMessages([msg({ code: '[Warn-3005] line -1: old', message: 'old' })]);
  expect(g.headline).toBe('old'); expect(g.diagnosis).toBeNull();
  expect(targetOf(g.messages[0])).toBeNull(); expect(occurrenceLabel(g.messages[0])).toBe('—');
});
it('maps locations to tabs', () => {
  expect(targetOf(msg({ location: { section: 'mtd', metadata_key: 'sample[3]-species[1]', metadata_group: 'sample' } })))
    .toEqual({ tab: 'metadata', group: 'sample', key: 'sample[3]-species[1]' });
  expect(targetOf(msg({ location: { section: 'sml', row_index: 1, column: 'reliability' } })))
    .toEqual({ tab: 'summary', rowIndex: 1, column: 'reliability' });
  expect(targetOf(msg({ location: { section: 'smh', column: 'abundance_assay[1]' } })))
    .toEqual({ tab: 'summary', column: 'abundance_assay[1]', header: true });
  expect(targetOf(msg({ location: { section: 'com' } }))).toBeNull();
});
it('labels occurrences', () => {
  expect(occurrenceLabel(msg({ location: { section: 'sml', row_index: 1, column: 'reliability' } }))).toBe('SML row 2 · reliability');
  expect(occurrenceLabel(msg({ location: { section: 'mtd', metadata_group: 'sample' } }))).toBe('metadata group: sample');
  expect(occurrenceLabel(msg({ line_number: 12 }))).toBe('line 12');
});
```

- [ ] **Step 2: Run to verify they fail**

Run: `npm --prefix frontend test -- --include='**/message-groups.spec.ts'`
Expected: FAIL (module not found).

- [ ] **Step 3: Implement `message-groups.ts`.**
- [ ] **Step 4: Run the test**

Run: `npm --prefix frontend test -- --include='**/message-groups.spec.ts'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/projects/webapp/src/app/pages/result/message-groups.ts frontend/projects/webapp/src/app/pages/result/message-groups.spec.ts
git commit -m "SPA: group validation messages and map their locations to document targets"
```

### Task C3: SPA — grouped Messages tab

**Files:**
- Modify: `frontend/projects/webapp/src/app/pages/result/messages-tab.component.{ts,html,scss,spec.ts}`

**Interfaces:**
- Consumes: Task C2.
- Produces:

```ts
export interface ShowRequest { group: MessageGroup; index: number; all: boolean; }
// MessagesTabComponent
readonly messages = input.required<readonly ValidationMessage[]>();
readonly profileLabel = input<string | null>(null);   // e.g. "lipidcompass-summary-2.0 v3"
readonly selected = input<string | null>(null);       // MessageGroup.key of the shown group
readonly show = output<ShowRequest>();
```

Behaviour: view toggle `grouped` (default) / `flat` ("Show as flat list" / "Group by rule" button); filters (text, level, category) apply to both views and to the occurrences inside groups; grouped view pages 25 groups per page with the existing pager; flat view is today's table plus a `Location` column (`occurrenceLabel`). Group card: level chip, headline (`.fix`), count badge `N occurrences` when N > 1, diagnosis (CSS clamp to 3 lines, full text in `title`), meta line (`code NNNN` from `typeCode`, category label, `rule <rule_id>` and `profileLabel` when `ruleId`), occurrence chips for messages with a target (first 8, then a `+N more` button that shows all), actions `Show first →` (index of the first message with a target, `all:false`) and `Highlight all N` (when ≥2 targets, `all:true`). Chips and actions are `<button type="button">`; a group without targets shows no chips or actions. Selected group gets `.grp--sel` and `aria-current="true"`. Level colours: existing `lifs-lvl--*` classes; left border colour per level.

- [ ] **Step 1: Write the failing tests** (extend `messages-tab.component.spec.ts`)

```ts
it('shows groups with headline, count and chips by default', async () => {
  // 3 messages: one Error-2009 (suggestion ERR_FIX, smh column), two Info-3010 rule sample_species_may
  // (suggestion INFO_FIX, keys sample[1]-species[1] and sample[2]-species[1])
  expect(texts('.grp .fix')).toEqual([ERR_FIX, INFO_FIX]);
  expect(text('.grp:nth-of-type(2) .count')).toBe('2 occurrences');
  expect(texts('.grp:nth-of-type(2) .chip')).toEqual(['sample[1]-species[1]', 'sample[2]-species[1]']);
});
it('emits show for a chip and for highlight all', async () => {
  click('.grp:nth-of-type(2) .chip:nth-of-type(2)'); expect(shown).toEqual({ group: expect.anything(), index: 1, all: false });
  click('.grp:nth-of-type(2) .act-all'); expect(shown.all).toBe(true);
});
it('collapses chips after 8', async () => { /* 11 occurrences -> 8 chips + "+3 more"; click -> 11 chips */ });
it('renders old records without chips or actions', async () => { /* no suggestion/location -> headline = message, no .chip, no .act */ });
it('filters apply inside groups', async () => { /* text filter matching one of two occurrences -> count badge absent, one chip */ });
it('flat list has a Location column', async () => { click('.view-toggle'); expect(headerTexts()).toContain('Location'); });
it('marks the selected group', async () => { /* selected = key -> .grp--sel with aria-current */ });
```

- [ ] **Step 2: Run to verify they fail**

Run: `npm --prefix frontend test -- --include='**/messages-tab.component.spec.ts'`
Expected: FAIL.

- [ ] **Step 3: Implement.** Keep the existing filter controls and pager markup; existing flat-view tests keep passing after switching to the flat view first.
- [ ] **Step 4: Run the test**

Run: `npm --prefix frontend test -- --include='**/messages-tab.component.spec.ts'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/projects/webapp/src/app/pages/result/messages-tab.component.*
git commit -m "SPA: grouped, fix-first messages with occurrence chips"
```

### Task C4: SPA — highlight in the Metadata tab

**Files:**
- Modify: `frontend/projects/webapp/src/app/pages/result/metadata-tab.component.{ts,html,scss,spec.ts}`
- Modify: `frontend/projects/webapp/src/styles/_lifs-tokens.scss` (`--lifs-highlight: #FFF1B8; --lifs-highlight-border: #E0B400;`)

**Interfaces:**
- Consumes: Task C1 `getMetadataPage({ id, group, page, size, key })`.
- Produces:

```ts
export interface MetadataHighlight { group: string; keys: readonly string[]; focusKey: string | null; }
// MetadataTabComponent
readonly highlight = input<MetadataHighlight | null>(null);
// MetadataGroupComponent
readonly highlightKeys = input<readonly string[]>([]);
readonly focusKey = input<string | null>(null);
```

Behaviour: the panel of `highlight.group` is expanded (others keep their state; without a highlight the first panel starts expanded as today); a group-only highlight (`keys` empty) outlines that panel (`.panel--hl`, 2px `--lifs-highlight-border`). In the group, a `focusKey` request loads with `key` (the server picks the page; the component adopts `data.page`); entries whose key is in `highlightKeys` get `.entry--hl` (background `--lifs-highlight`, inset 3px left `--lifs-highlight-border`); the focused entry gets `tabindex="-1"`, is scrolled into view (`block: 'nearest'`) and focused after render (`afterNextRender`).

- [ ] **Step 1: Write the failing tests**

```ts
it('expands the highlighted group and asks for the page that holds the key', async () => {
  set(highlight, { group: 'sample', keys: ['sample[60]-description'], focusKey: 'sample[60]-description' });
  expect(api.getMetadataPage).toHaveBeenCalledWith(expect.objectContaining({ group: 'sample', key: 'sample[60]-description' }), expect.anything());
  // stub returns page 1 containing the key
  expect(el('.entry--hl .key').textContent).toBe('sample[60]-description');
  expect(document.activeElement).toBe(el('.entry--hl'));
});
it('marks every highlighted key on the page', async () => { /* keys of 3 -> 3 .entry--hl */ });
it('outlines a group-only highlight', async () => { /* keys [] -> .panel--hl on the sample panel */ });
```

- [ ] **Step 2: Run to verify they fail**

Run: `npm --prefix frontend test -- --include='**/metadata-tab.component.spec.ts'`
Expected: FAIL.

- [ ] **Step 3: Implement**; add the two tokens to `_lifs-tokens.scss` with a comment that `--lifs-ink` on `--lifs-highlight` passes WCAG AA for text (check the ratio with any contrast checker and put the number in the comment; the border colour is decoration only).
- [ ] **Step 4: Run the test** — same command, Expected: PASS.
- [ ] **Step 5: Commit**

```bash
git add frontend/projects/webapp/src/app/pages/result/metadata-tab.component.* frontend/projects/webapp/src/styles/_lifs-tokens.scss
git commit -m "SPA: open and highlight metadata entries named by a message"
```

### Task C5: SPA — jump and highlight in the table tabs

**Files:**
- Modify: `frontend/projects/webapp/src/app/pages/result/table-tab.component.{ts,html,scss,spec.ts}`

**Interfaces:**
- Produces:

```ts
export interface TableHighlight { rows: readonly number[]; focusRow: number | null; column: string | null; header: boolean; }
// TableTabComponent
readonly highlight = input<TableHighlight | null>(null);
```

Behaviour on a new highlight: `resetColumnsState()` (filters, sort cleared); `picked` set to `null` when it does not contain `column`; window: column index `c` in `allColumns` → `windowStart = c < MAX_COLUMNS ? 0 : Math.floor(c / MAX_COLUMNS) * MAX_COLUMNS`; `pageIndex = Math.floor(focusRow / size)` when `focusRow != null`. If `allColumns` is not loaded yet, apply the window after the first page arrives (one pending highlight). Rendering: rows whose `row.index` is in `rows` get `.row--hl`; the cell in `column` of those rows gets `.cell--hl`; `header` highlights the `<th>` of `column`; a note `N more on other pages` (`aria-live="polite"`) counts `rows` not on the current page; the focused row's highlighted cell (or the row's first cell) gets `tabindex="-1"` and focus after render.

- [ ] **Step 1: Write the failing tests**

```ts
it('clears filters and sort and opens the page of the row', async () => {
  // size 50, filter typed, sort set; highlight focusRow 120
  set(highlight, { rows: [120], focusRow: 120, column: 'reliability', header: false });
  expect(lastQuery()).toMatchObject({ page: 2, sort: undefined, filter: undefined });
});
it('moves the column window to show a far column', async () => {
  // 130 columns; column index 120 -> windowStart 100 -> columns param [col0, col100..col149]
  set(highlight, { rows: [0], focusRow: 0, column: 'col120', header: false });
  expect(lastQuery().columns).toEqual([['col0,' + range(100, 150).map((i) => 'col' + i).join(',')]]);
});
it('drops a picked column set that hides the column', async () => { /* picked [a,b]; column c -> columns param without picked */ });
it('tints rows, outlines the cell and counts rows on other pages', async () => {
  set(highlight, { rows: [1, 2, 300], focusRow: 1, column: 'reliability', header: false });
  expect(all('.row--hl').length).toBe(2); expect(all('.cell--hl').length).toBe(2);
  expect(text('.hl-more')).toBe('1 more on other pages');
});
it('highlights a header cell', async () => { /* header true -> th.col--hl for the column, page 0 */ });
```

- [ ] **Step 2: Run to verify they fail**

Run: `npm --prefix frontend test -- --include='**/table-tab.component.spec.ts'`
Expected: FAIL.

- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run the test** — same command, Expected: PASS (existing table tests unchanged).
- [ ] **Step 5: Commit**

```bash
git add frontend/projects/webapp/src/app/pages/result/table-tab.component.*
git commit -m "SPA: jump to and highlight table rows, cells and headers"
```

### Task C6: SPA — side-by-side result page (layout B, fallback A)

**Files:**
- Modify: `frontend/projects/webapp/src/app/pages/result/result.page.{ts,html,scss,spec.ts}`

**Interfaces:**
- Consumes: Tasks C2–C5 (`ShowRequest`, `targetOf`, `DocTab`, `MetadataHighlight`, `TableHighlight`).
- Produces (internal to the page):

```ts
interface Focus { group: MessageGroup; targets: DocTarget[]; index: number; all: boolean; }
protected readonly wide: Signal<boolean>;               // BreakpointObserver '(min-width: 1100px)'
protected readonly focus = signal<Focus | null>(null);
protected readonly docTab = signal<DocTab>('metadata');
protected readonly metadataHighlight: Signal<MetadataHighlight | null>;
protected readonly tableHighlight: Signal<TableHighlight | null>;  // for the table of docTab
protected onShow(req: ShowRequest): void;
protected step(delta: 1 | -1): void;
protected backToMessage(): void;
```

Behaviour:
- `targets` = the group's messages mapped with `targetOf`, nulls dropped; `onShow` sets `focus` with the request's index translated to the target list, and `docTab = targets[index].tab`.
- Highlights derive from `targets[index]`; with `all`, from every target in the same tab (metadata: all keys of the same group; tables: all `rowIndex` values; header targets: that column).
- **Wide and parsed** (`wide()` and outcome ≠ `NOT_PARSED`): two columns (`grid-template-columns: minmax(360px, 40%) 1fr`, gap 18px); left the Messages tab content, right a tablist of Metadata / Summary / Features / Evidence bound to `docTab` (same roving-tabindex keyboard handling); the Messages tab is not in the right tablist.
- **Narrow, or NOT_PARSED:** today's single tablist; `onShow` switches `active` to `docTab`; above the panel a bar `← Back to message · <headline> · i of N ‹ ›` (`.back-bar`), shown while `focus` is set and the active tab is not Messages; `backToMessage()` activates Messages and focuses the group card's first button.
- `‹ ›` (`step`) move `index` within `targets` (disabled at the ends) in both layouts; in the wide layout the stepper sits above the right pane.
- An `aria-live="polite"` region reads `Showing occurrence i of N`.
- `profileLabel` for the Messages tab: `CATALOG` → `<id> v<version>`, `CUSTOM` → `custom mapping`, else `null`.

- [ ] **Step 1: Write the failing tests** (extend `result.page.spec.ts`; control width with a `BreakpointObserver` stub returning `{ matches }`)

```ts
it('shows messages and the document side by side on wide screens', async () => {
  wide(true); await renderFinished(recordWithLinkedMessages);
  expect(el('.split .messages')).toBeTruthy();
  expect(roleNames('tab')).toEqual(['Metadata', 'Summary', 'Features', 'Evidence']);
});
it('a chip click on a wide screen changes only the right pane', async () => {
  click('.chip'); // sml row 1
  expect(selectedTab()).toBe('Summary'); expect(el('.split .messages')).toBeTruthy();
  expect(el('[aria-live] .occ').textContent).toContain('Showing occurrence 1 of');
});
it('falls back to jumping on narrow screens and offers the way back', async () => {
  wide(false); await renderFinished(recordWithLinkedMessages);
  click('.chip'); expect(selectedTab()).toBe('Summary');
  expect(el('.back-bar').textContent).toContain('Back to message');
  click('.back-bar .back'); expect(selectedTab()).toMatch(/^Messages/);
});
it('steps through occurrences', async () => { /* group with 3 targets; › twice -> 3 of 3, › disabled */ });
it('stays single-column for unparsed files even when wide', async () => {
  wide(true); await renderFinished({ ...notParsedRecord }); expect(el('.split')).toBeNull();
});
```

- [ ] **Step 2: Run to verify they fail**

Run: `npm --prefix frontend test -- --include='**/result.page.spec.ts'`
Expected: FAIL.

- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run all frontend tests**

Run: `npm --prefix frontend test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/projects/webapp/src/app/pages/result/result.page.*
git commit -m "SPA: side-by-side messages and document with occurrence stepping"
```

### Task C7: e2e, accessibility, release notes

**Files:**
- Create: `frontend/e2e/fixtures/linked-messages.mzTab`, `frontend/e2e/fixtures/linked-messages-mapping.xml`
- Modify: `frontend/e2e/validator.spec.ts`, `docs/release-notes/2.0.0.md`

**Interfaces:**
- Consumes: the whole of Part C, and 1.0.8-SNAPSHOT (Part B).

- [ ] **Step 1: Create the fixtures.** `linked-messages.mzTab` = `frontend/e2e/fixtures/lipidomics-example.mzTab` with: `MTD	instrument[1]-name` value `[XMS, MS:1001911, Q Exactive , ]` (undefined CV label → `CvUndefinedInMetadata` at `instrument[1]-name`); the `abundance_assay[1]` column removed from `SMH` and from the `SML` row (→ `NotDefineInHeader`, `smh` column). `linked-messages-mapping.xml` = the `doctype-mapping.xml` structure without the DOCTYPE, with one rule: `id="sample_species_may"`, `cvElementPath="/metadata/sample/@species"`, `requirementLevel="MAY"`, `cvTermsCombinationLogic="OR"`, scope `/metadata/sample`, one `CvTerm` `NCBITaxon:1` "root" `allowChildren="true" useTerm="false"` with a `CvReference` `NCBITaxon`. The fixture has no species, so the rule hits `EmptyRuleHandler` and needs no OLS lookup (Ruling 8).
- [ ] **Step 2: Write the e2e test** `linked messages open their place in the document` (desktop viewport 1280×800): upload with the custom mapping (use the helper of the existing `custom mapping with DOCTYPE is rejected` test, but expect success), level INFO; on the result: a group headline starting `Add a cv[n] entry for "XMS"` is visible; its chip `instrument[1]-name` click → the Metadata pane shows `instrument[1]-name` with class `entry--hl` and focus; the group starting `Optionally` or `Add a value for sample[n]-species` → chip `metadata group: sample` → the `sample` panel has `.panel--hl`; the `abundance_assay[1]` group → chip `SMH · abundance_assay[1]` → Summary pane with a highlighted header cell. Then the narrow variant (viewport 800×900): chip click switches to the Metadata tab and `Back to message` returns.
- [ ] **Step 3: Add axe** on the result page with an occurrence highlighted to the existing accessibility test list (no serious or critical violations).
- [ ] **Step 4: Run e2e** (server started as in `.github/workflows/maven.yml` e2e job, port 8083, context `/mztabvalidator`)

Run: `npm --prefix frontend run e2e`
Expected: all tests PASS.

- [ ] **Step 5: Release notes**: add a section to `docs/release-notes/2.0.0.md`: messages grouped by rule with the suggested fix first; links to the metadata entry, table cell or column; side-by-side view on wide screens; CSV report columns `suggestion,rule_id,section,row,column,metadata_key,metadata_group` and the escaped `code` column; requires jmzTab-m 1.0.8-SNAPSHOT; semantic "term not allowed" messages now appear once per element.
- [ ] **Step 6: Full verification**

Run: `./mvnw -B verify`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add frontend/e2e docs/release-notes/2.0.0.md
git commit -m "e2e and release notes for linked validation messages"
```

- [ ] **Step 8: STOP — ask before pushing.** On approval: push `feat/linked-messages` and open a PR (base `feat/validator-spa` while PR #77 is open, else `master`). Before `-dev`: the project lead has reviewed the fix texts (spec §6.4).
