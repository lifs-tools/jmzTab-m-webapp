# Start a New mzTab-M File — Design

Status: revised 2026-11-08 after a builder check and an evidence review. It builds on:

- the editor design (`2026-10-31-mztabm-editor-design.md`, phases 2a–2c);
- the engines design (`2026-10-30-mztabm-version-engines-design.md`);
- the pre-deploy plan (`../plans/2026-11-08-editor-pre-deploy.md`).

It replaces "New files" in editor design §5.7 and the landing page's "Start a new file" in §5.10.

**Markers:**

- **[lead]**: something the project lead said or decided.
- **[my call]**: a choice I made that the lead has not ruled on.
- **[evidence]**: a question raised by the builder check, settled by a critical review from the cited code, designs or spec text. The lead asked for such questions to be settled this way rather than asked (2026-11-08).
- **[verify]**: a fact the builder must check before relying on it.

---

## 1. Intent

**Who starts a new file, and why.** A researcher who wants to submit results to a repository. **[lead]**

- **The main route is MetaboLights.** The researcher writes an mzTab-M file here, converts it with `mztab2mtbls`, and submits the result to MetaboLights. A native MetaboLights submission may follow later, at low priority.
- **The editor should also become the primary way to make a LipidCompass submission.** It would partly replace the LipidCompass wizard, which only accepts finished files and validates them. Today a submitter fixes the file locally and uploads it again, or worse, deletes the old file and uploads the revised one.
- **A valid MetaboLights file should also import cleanly into LipidCompass.** The CVs the two use may differ for now.

**What the researcher already has [lead]:**

- **Results**, in one of three forms:
  - a summary table made by hand;
  - third-party tool output;
  - ideally, an mzTab-M file written by an implementing tool (xcms, MS-DIAL, mzmine and others).
- **A methods description**, as text.
- **A samples × study factors table**, usually. Study factors are also called variable groups.
- **Raw files and mzML files.** One sample can have several measurements, so several raw or mzML files.

**Why `mztab2mtbls` and 2.1 matter [lead].**

- `mztab2mtbls` works with 2.0. It needs at least the metadata (MTD) and Summary (SML) sections, and ideally all four sections.
- A 2.1 file carries the protocols. A 2.0 conversion lacks them, which leaves validation warnings in MetaboLights that the submitter must resolve by hand.

The evidence (§3) makes the reason concrete: the converter reads protocols only from `protocol[n]`. Neither jmzTab-m line implements `protocol[n]` yet, so 2.1 protocol support in jmzTab-m is a prerequisite of the protocol part of this design (§5.4, §6).

**What the editor helps with.** It helps the researcher turn what they have into a valid file quickly and repeatably. Each kind of input goes through a published, declarative mapping, so other tools can produce the same result from the same input:

- metadata (Lipidomics Checklist JSON, the samples table);
- the methods text, read by a self-hosted language model;
- the summary table;
- the data files' names and mzML headers.

**Why one shared vocabulary [lead, 2026-11-08].** The LipidCompass submission template v3 already defines a field dictionary that maps onto mzTab-M. It is used by curators in `lipidcompass-submissions`. This design makes that dictionary the shared vocabulary. Every input maps into it, and one mapping per mzTab-M version maps it onto mzTab-M. This avoids drift between the v3 template and the editor, and it gives one generic mechanism instead of a converter per input.

---

## 2. Goals, non-goals, anti-goals

### Goals

1. **Start a new file from the navigation bar** [lead]. The researcher chooses:
   - the mzTab-M version;
   - for 2.1, the content profile (`mzTab-profile`);
   - the repository rules (validation profile) for semantic validation.

   The editor opens a working copy that validates from the first moment.
2. **Fill the file from what the researcher has.** Each kind of input adds content to the open working copy:
   - the samples × study factors table;
   - the data files (names and mzML headers);
   - the summary table;
   - Lipidomics Checklist JSON;
   - the methods text;
   - an mzTab-M file from a tool.

   Everything an import proposes is shown first and applied as ordinary, undoable edits.
3. **A shared submission vocabulary and published mappings** [lead]. They are world-public and reusable by any tool, MetaboLights and LipidCompass included. They are versioned, and conformance examples ship with them. The v3 template is generated from the same vocabulary.
4. **The finished file serves both repositories.** A file finished in the editor converts with `mztab2mtbls` without errors and imports into LipidCompass. For LipidCompass, this holds for 2.0 files until LipidCompass reads 2.1 (§3).
5. **Repeatability.** A file records which vocabulary and mapping versions produced it. The same inputs give the same file.

### Non-goals

- **A second MetaboLights online editor** [lead]. ISA-Tab editing, MetaboLights-only metadata, and anything `mztab2mtbls` or MetaboLights already does stay out of scope.
- **Storing vendor raw files or mzML files** [lead]. Only file names and values read from mzML headers leave the browser.
- **Native MetaboLights submission.** Later and low priority [lead].
- **Computing features or evidence (SMF/SME) from data files.** Tools do that. A tool's mzTab-M brings them with it.
- **Uploading to a repository from the editor.** That belongs to the LipidCompass integration (phase 2c) and later.
- **A general ontology browser.** Term pickers offer what the repository rules allow, plus search.
- **Writing new validation profiles** [evidence, Q2]. This design uses the profiles the catalog has.
- **User accounts** in the public validator (SPA design §2).

### Anti-goals (failure even if it "works")

- **Model output reaching the file without the researcher's review.** It must not, even when it validates.
- **A file the validator accepts but `mztab2mtbls` rejects** for something the editor could have known.
- **Mapping logic hidden in code.** A behaviour another tool cannot reproduce from the published files is a failure.
- **The v3 template and the editor disagreeing** about a field, its value list or its mzTab-M target.
- **Breaking v3 workbooks that submitters have already filled in.**
- **Data files, or their contents beyond header values, leaving the researcher's machine.**
- **Numbers that came from a language model.** Quantities, masses, sample counts and dates must never come from it.
- **The webapp parsing mzTab-M beyond the version line** (engines design §2). It is kept by putting everything that reads mzTab-M keys into the engines (N7).

---

## 3. Current state (facts this design relies on, 2026-11-08)

**LipidCompass submission template v3** (`lifs-tools/lipidcompass-submissions`, which is **private** and MIT-licensed):

- **Sources.** `config/submission-template-v3/fields.yaml` holds the fields. Its header calls it "the single source for the workbook layout", and the field ids in row 1 and column A are "the machine contract". `options.yaml` holds the drop-down lists. `tools/build_submission_template.py` generates three things from them:
  - the workbook;
  - `templates/v3/submission-template-schema.json`;
  - `options.resolved.json` (OLS labels, cached).
- **The reader** (`read_submission_template.py`) loads the repository's current schema JSON, not the workbook's embedded `_schema`. Four filled v3 workbooks exist on `origin/master` (bb8dcbf), `submissions/LCS-0012{1..4}/supplementary/`, besides the example.
- **Pin.** Facts about the converter refer to `origin/master` bb8dcbf (2026-11-08, after PRs #226 and #227).
- **`submission-template-schema.json` is a field dictionary, not a JSON Schema.** Its top-level keys are:
  - `template`;
  - `conventions` (the sheet layout);
  - `sheets`: `study`, `contacts`, `datasets`, `samples`, `factors`, `lipids`;
  - `quantities`;
  - `lists`: 31 value lists.
- **The fields.** There are 72. Each has `id`, `label`, `required` (true, false, "recommended", or a condition in words), `list`, `type` (text, number, date, url, email, doi, orcid, version), `help` and `example`. Some also have `ontologies` and `version_of`.
- **The mzTab-M targets are prose, and for 2.0 only.** 48 fields name a target in `mztab`, for example:
  - `sample[n]-custom[k] (NCIT:C28421 Sex)`;
  - `software[n] (parameter value)`;
  - `mzTab-ID (prefix)`.

  The fields without a target are mostly LipidCompass-specific: licence, embargo, study type, acquisition mode, missing-value encoding, and others.
- **The value lists.** They carry accessions checked against OLS, preferred ontologies first. They are ordered by how often each value occurs in the 189 published LipidCompass files. Free text is always accepted and becomes a user parameter for curators.
- **The real mapping is code.** `tools/convert_submission_template.py`, about 435 lines, does the following:
  - writes one mzTab-M 2.0 file per dataset row;
  - writes **two** `sample_processing` entries (`:200-204`): one for extraction plus preparation, one for separation plus column plus details;
  - writes the LipidCompass-only fields as MTD `custom[]`;
  - makes study variables from the factors marked "primary";
  - computes study-variable means and coefficients of variation;
  - parses lipid names with Goslin (`goslin:` identifier, formula, mass);
  - defaults reliability (1 for internal standards, 3 when Goslin parses the name, 4 otherwise);
  - writes `opt_global_original_name` and `opt_global_internal_standard`;
  - writes for every run the dataset's raw-data link, or `file://<sample name>` (`:225-231`);
  - declares only the CVs it uses.

  Its output validates with jmzTab-m 1.0.8 with no errors or warnings (lipidcompass-submissions PRs #226 and #227).

**Lipidomics Checklist** (`lifs-tools/lipidomics-checklist`, public, MIT):

- **The export** (`FormsManager.py` `export_report`, `:2830-2891`) is `{"checklist": …, "samples": [...], "lipid_classes": [...]}`, delivered as a base64 data URL. Each element is a form state with keys `pages`, `current_page`, `max_page`, `creation_date`, `version`. `import_report` requires the `checklist` key.
- **Fields are addressed by `name`; choices by `name#value`.** Examples: `extraction_method#2-phase_system`, `2-phase_system#mtbe`. An "other" choice has a matching `other_<name>` text field.
- **Sample forms describe sample *sets*:** set name, type, storage, freeze-thaw cycles. They do not describe individual samples.
- **Lipid-class forms describe identification and quantification per class.** Neither sample sets nor lipid classes have an mzTab-M place per sample or per class.
- **What it lacks.** It has no CV terms, polarity, units, study variables or publication.

**`mztab2mtbls`** (`EBI-Metabolights/mztabm2mtbls`, `main`, updated 2026-10-15, Apache-2.0):

- **How it reads files.** It reads TSV through the Python `mztab_m_io`/`pymztabm` model, not through jmzTab-m, and it turns `"null"` into None. It has no version gate: its own fixture `test/data/MTBLS263/mztabm/MTBLS263.mztab` declares 2.0.0-M and carries 33 `protocol[` lines and `assay[n]-protocol_refs`.
- **Protocols.**
  - It maps `metadata.protocol` (`mapper/metadata/metadata_protocol.py`), matching protocol **names** case-insensitively against its default ISA protocols: Sample collection, Extraction, Mass spectrometry, Data transformation, Metabolite identification (`utils.py` `create_initial_protocols`, `:165-219`). Any other name, such as "Chromatography" in its fixture, becomes an extra ISA protocol.
  - It renames parameters through `parameter_name_map`: "mass spectrometry instrument", "ionization type", "instrument class", "scan polarity", "scan m/z range", "chromatography instrument", "chromatography column", "chromatography separation", "guard column", "autosampler model", "post extraction", "derivatization".
- **2.0 `sample_processing` is not used.** `MetadataSampleProcessingMapper` is imported (`converter.py:24`) but not in the mapper list (`:203-216`). A 2.0 file's methods therefore never reach the ISA protocols.
- **Data files.** It names each assay's data file `"FILES/" + basename(ms_run.location)`, or an empty string when the location is None (`metadata_assay.py:380-390`).
- **MetaboLights' real acceptance check** is the OPA `mtbls-validation` bundle, run after conversion (`commands/convert_and_validate_submission.py`).

**jmzTab-m:**

- **2.1 does not implement `protocol[n]`.** On `origin/master` (6b8d9b86, 2026-11-02), `MetadataElement` has `STUDY_VARIABLE_GROUP` but no `PROTOCOL`, and `mzTab_m_openapi.yml` has no protocol. A `protocol[1]-name` line gets Error-1009. 1.0.x (2.0) has no protocol either. No jmzTab-m issue covers it. HUPO-PSI/mzTab-M #95 (open) covers the spec's documentation.
- **The spec contradicts itself on the assay link.** The field is `assay[1-n]-protocol_refs`, but the example writes `protocol_ref` (`mzTab_m_2_1_schema.adoc:703` vs `:712`). mztab2mtbls' fixture uses `protocol_refs`.
- **`ms_run[n]-location` = `null`.** Both lines give Warn-2001, and then Error-2008 from `MsRunValidator` (master `:40-45`, 1.0.x `:41-45`). Yet the 2.0 release spec already says "a 'null' MUST be used" when unknown, and 2.1 (HUPO-PSI/mzTab-M#154, open) repeats it and adds "SHOULD be absolute". The open issues are jmzTab-m #251 (accept `null`) and #250 (Warn-1040 for relative URIs).
- **`ms_run[n]-scan_polarity` is mandatory** (Error-2008, `MsRunValidator.java:46-52`).

**LipidCompass** parses submissions with jmzTab-m 1.0.6 (`lipidcompass/pom.xml:119`), so it reads 2.0 only. Its wizard has 10 steps, saves form JSON only, and is fixed at mzTab-M 2.0. It has Keycloak/OAuth2 login.

**jmzTab-m-webapp** (`feat/validator-spa`):

- **The 2a repair editor exists.** It has:
  - working copies, stored as SQLite text lines and cells;
  - repair operations: `InsertLine`, `InsertColumn`, `SetCell` and the others (`EditOperation.java`);
  - revisions, undo and redo;
  - guidance and quick fixes.
- **2b is designed but not built**, and has no plan yet. `OlsMappingCvSuggestionService` exists in the engine code but is not exposed. There is no skeleton or field-rules operation.
- **The catalog's validation profiles** are only `mztab-m-2.0-default` (1.0.0, 1.1.0) and `mztab-m-2.1-default` (1.0.0) (`server/src/main/resources/validation-profiles/index.json`).
  - A LipidCompass profile exists only as a private, unfinished 2.0 file (`lipidcompass-submissions/config/validation/mzTab_2_0-M_summary-mapping.xml`). It enters the catalog "once finalized" (SPA design M15).
  - HUPO-PSI's MetaboLights mapping (`schema/mzTab_2_1-M_metabolights_mapping.xml`, CC-BY-4.0) is 2.1-only, has 29 rules, and has no protocol or study-variable-group rules.
- **Profiles can only express rules on CV-term paths** (CvMapping XML).
- **Version routing.** Production runs only the 2.0 engine until jmzTab-m 2.1.0 is released (engines design E10). A version is offered only where its engine is configured (E13). The editor preselects `engines.default-version` (engines §5.4).
- **The app is anonymous** (SPA design §2).
- **The webapp may not parse mzTab-M beyond the version line** (engines design §2, anti-goal).
- **The navigation bar has:** validate (landing), examples and docs. There is no "new" route.

**Language model endpoints [lead].** Both speak the OpenAI-compatible chat API:

- a vLLM endpoint on an NVIDIA L4, in the LipidCompass Docker swarm or over HTTPS from another OpenStack project;
- the de.NBI Cloud Bielefeld Bifrost gateway at `https://llm.bi.denbi.de/v1`. It is GDPR-compliant and keeps no user data beyond the session. It serves:
  - chat models: `Qwen3.6-35B-A3B`, `gemma-4-31B-it`, `gpt-oss-120b` and `Qwen3-Coder-Next`, plus `Qwen3.8-27B` hosted in Tübingen;
  - `bge-m3` embeddings;
  - the `bge-reranker-v2-m3` reranker.

**Grounding lessons from lipidcompass-submissions** (`docs/superpowers/specs/2026-07-31-*`):

- OLS4 lexical search with subtree checks works;
- ZOOMA is unreliable;
- only an exact label match can be trusted without review.

---

## 4. Decisions

| # | Decision | Status |
|---|---|---|
| N1 | The purpose is a file to submit: MetaboLights via `mztab2mtbls` first, LipidCompass as its primary submission path. | lead |
| N2 | The editor does not duplicate the MetaboLights online editor, and stores no raw or mzML files. | lead |
| N3 | The v3 field ids become the **shared submission vocabulary**. Every input maps into it; one mapping per mzTab-M version maps it onto mzTab-M. | lead, 2026-11-08 |
| N4 | The vocabulary and mappings are world-public and reusable by any tool. They are published in jmzTab-m-webapp; the generic parts may later go to HUPO-PSI/mzTab-M as auxiliary files. | lead |
| N5 | The methods text goes to a self-hosted vLLM endpoint or the de.NBI Bifrost gateway, through one OpenAI-compatible client configured on the server. | lead (endpoints); my call (one client) |
| N6 | Each data file becomes an `ms_run`, and each measurement an assay; files that are fractions of one measurement share an assay. mzML headers are read in the browser. | lead confirmed |
| N7 | **The working copy's text stays the only source of truth** (editor design R1). **The output mappings run in the engine of the copy's version.** The engine reads the copy's text and the vocabulary values, finds what the copy already has, and returns line edits. The webapp applies those edits with the existing repair operations. The webapp runs the input mappings, which turn formats into vocabulary values and read no mzTab-M. This keeps the engines design's anti-goal. | evidence (engines design §2); revised |
| N8 | **Imports propose; the researcher applies.** Every import ends in a preview of suggestions, and the accepted ones are applied as one undoable edit batch. | my call |
| N9 | **One document is one dataset.** A v3 workbook or a checklist that describes several datasets asks which one to use. Making several files at once is a later step. | my call |
| N10 | **The Python converter stays as it is.** It does not become an interpreter of the mappings. The Java output mapping for 2.0 must reproduce the converter's checked-in output for the v3 example, except for the differences listed with the test (§8). Whether lipidcompass-submissions later adopts the mappings is its own decision. | evidence (Q4); revised |
| N11 | **Three words, kept apart in the UI:** *mzTab-M version*; *content profile* (2.1 `mzTab-profile`: M, M+S, M+F, M+F+E, M+S+F+E); *repository rules* (the semantic validation profile). | my call |
| N12 | **The New-file form preselects `engines.default-version`** (2.0 in production until jmzTab-m 2.1.0, engines E10) and offers only versions whose engine is configured (E13). This replaces "2.1 by default". | evidence (Q3); revised |
| N13 | **`ms_run[n]-location`:** see §5.5. In short: a given base URL plus the file name, else the file's base name as a relative URI, `null` only when no file is known. Two data files with the same base name are refused. | evidence (Q1) |
| N14 | **One source of "required" per field.** For CV-term fields it is the validation profile. The vocabulary's per-repository requirement levels apply only to fields no profile rule covers. A build check fails when the two disagree for a path. New profiles are not written by this design. | evidence (Q2) |
| N15 | **Vocabulary 1.0.0 contains every v3 sheet and field id unchanged.** New groups and fields are additive and optional. The workbook generator renders only the v3 groups and fields, so the filled workbooks keep reading identically. | evidence (Q5) |
| N16 | **The checklist import reads the `checklist` form only**, keyed on its `version`. It lists `samples` and `lipid_classes` in the preview as "not imported". | evidence (Q7) |
| N17 | **Methods text is switched per deployment** by its endpoint setting. The public validator ships without an endpoint until the lead has de.NBI's terms for that use in writing. It runs on `-dev` for the evaluation, and later in the authenticated LipidCompass embedding. No accounts are built. | evidence (Q8) |
| N18 | **N-2 builds two engine operations:** skeleton and field rules. Exposing CV suggestions is optional in N-2. For imports, the text edits of N7 replace 2b's model edits. 2b's model edits stay scoped to the guided wizard, which is decided in its own plan. | evidence (Q6) |
| N19 | **Protocol names, types and parameter names follow mztab2mtbls' conventions** (its default ISA protocols, `parameter_name_map`, and its MTBLS263 fixture), so they land on MetaboLights' protocols instead of adding extra ones. | evidence (contradiction 2) |
| N20 | **2.1 `protocol[n]` output is gated on jmzTab-m implementing `protocol[n]`.** Until then, the 2.1 output mapping writes no protocols, and the 2.0 mapping writes `sample_processing` as v3 does. | evidence (contradiction 1) |

---

## 5. Design

### 5.1 User journeys

1. **Start a new file.**
   - The researcher chooses **New file** in the navigation bar, then the version, content profile and repository rules (one short form).
   - The engine writes a skeleton, and the editor opens it as a working copy.
   - The editor shows what is still missing for the chosen repository rules, as a checklist next to the document, alongside the validation messages.
2. **Start from a tool's file.**
   - The researcher opens an mzTab-M file from xcms, MS-DIAL, mzmine or another tool.
   - This is today's "Fix this file" path; the new part is the same **Add from…** panel as in journey 1, to supply what the tool did not write: methods, samples, study variables.
   - Upgrading a 2.0 tool file to 2.1 is §5.9.
3. **Add from a table.**
   - The researcher drops a samples table, or a summary table (CSV, TSV or XLSX), and confirms what each column is.
   - The editor then shows the suggested lines.
4. **Add the data files.**
   - The researcher chooses the raw and mzML files, or a folder.
   - The browser reads file names and mzML headers only, and pairs raw with mzML files by base name.
   - The editor then proposes `ms_run`s and assays and their link to samples.
5. **Add from the methods text** (where enabled, N17).
   - The researcher pastes the methods text.
   - The editor states where the text will be sent, then shows each suggested value next to the sentence it came from.
6. **Add from the Lipidomics Checklist.** The researcher drops the checklist's export; the editor shows the suggested lines.
7. **Finish.** The file validates with the chosen repository rules, and the researcher downloads it.
   - Later phases may add "convert for MetaboLights" (`mztab2mtbls`) and "submit to LipidCompass" (phase 2c).

### 5.2 The submission vocabulary

**What it is.** The v3 field dictionary, moved and extended without changing what exists (N15). Each field has:

- a stable `id`, the machine contract;
- a label and help text (English);
- a type (text, number, date, url, email, doi, orcid, version, plus the new term and term-or-text);
- cardinality;
- a requirement level, per repository, only where no profile rule covers the field (N14);
- an optional value list;
- for terms: the allowed ontologies, preferred first, and allowed subtree roots where known.

**Groups in 1.0.0:**

| Group | Origin | Rendered in the v3 workbook |
|---|---|---|
| `study`, `contacts`, `datasets`, `samples`, `factors`, `lipids`, `quantities` | the v3 sheets, field ids unchanged | yes, exactly as today |
| `runs` | **new**, optional: one row per data file. It holds the file name, format, paired raw/mzML file, sample, assay, fraction, and polarity, instrument and fragmentation from the mzML header (N6). | no |
| `lipid` extension marker | **new**: Goslin parsing and shorthand nomenclature apply when the dataset declares lipid nomenclature. Feature names that are not lipids stay as written. | no |

**Fallbacks inside the dataset.** Dataset-level fields stay the fallback for run-level ones. For example, the dataset's `scan_polarity` (required in v3) fills `ms_run[n]-scan_polarity` when no mzML header gave one. That field is mandatory in jmzTab-m, which is why the fallback is needed (§3).

**Value lists.** They are split out of the vocabulary into one file per list. Each entry can be tagged with the repositories that accept it. LipidCompass's ordering by frequency is kept.

**Versioning.** The vocabulary uses semantic versioning:

- removing or renaming a field id is a major change;
- adding a field or a list entry is a minor change.

The v3 template keeps `template.version` (3.0.0, the workbook layout's version, which filled workbooks carry). It gains `template.vocabulary`, the vocabulary version it was generated from [evidence, N-1 plan review].

**What lipidcompass-submissions keeps.** The v3 workbook stays a submission route. `build_submission_template.py` reads the vocabulary from its published location, pinned by version, instead of its own `fields.yaml` and `options.yaml`. Those two files are deleted there once the move is done.

**Help texts stay as they are [evidence].** They mention curation codes (P6, B21 and others), but the v3 workbook that carries them is already public: it is downloaded from lipidcompass.org (docs design D11). Rewording them would change the workbook for no gain.

**The vocabulary instance is the v3 reader's JSON [my call].** It is what `read_submission_template.py` writes: `study`, `contacts`, `datasets` (each with its `quantities`: `sheet`, `layout`, `samples`, `lipids`, `values`), `samples` (with `factors`), `factors` and `lipids`. List values appear as `{"text": …}` or `{"cv", "accession", "name"}`. Every input mapping produces this shape, so the existing reader is already a conformant `v3-workbook` input implementation.

### 5.3 Mapping files

There are two kinds of mapping. Both are plain YAML data, described by a JSON Schema for the mapping language that is published with them.

**Input mappings (input format → vocabulary).** There is one per input format and format version. The webapp runs them; they read no mzTab-M.

- `v3-workbook` (one to one);
- `lipidomics-checklist` (the `checklist` form, per checklist `version`, N16);
- `samples-table`;
- `summary-table`;
- `mzml-header`;
- `methods-text`.

How each one addresses its input:

| Input | Addressed by |
|---|---|
| JSON | field `name` and choice `name#value` (checklist), or a JSON Pointer |
| tables | the column role the researcher confirmed (§5.5), with header synonyms suggested by the mapping (`"Sample Name"`, `"sample_id"` → `samples.sample_name`) |
| mzML | CV accession within its element: `<instrumentConfiguration>` components, `<scanSettings>`, the polarity term of the first spectrum |
| methods text | the field list and its allowed subtrees; the mapping is also the source of the JSON Schema the model must fill (§5.6) |

**Output mappings (vocabulary → mzTab-M).** There is one per mzTab-M version (`mztab-2.0.0-M`, `mztab-2.1.0-M`). The engine of that version runs them (N7). Each rule names:

- **Source:** a vocabulary field.
- **Target:** an mzTab-M element by its key, with index placeholders, for example:
  - `sample[{sample}]-species[{k}]`;
  - `sample[{sample}]-custom[{k}]` with a fixed parameter `[NCIT, NCIT:C28421, Sex, {value}]`;
  - `abundance_assay[{assay}]`.
- **Rendering:** how the value becomes a cell. It is a parameter for terms, a user parameter for free text, or text, number or URI.
- **Condition:** an optional condition on other fields.

**Transforms.** A mapping may use only a fixed set of transforms. Anything outside this set belongs in a tool, not in a mapping. The set is amended after the N-1 plan review (2026-11-08) [evidence]: rendering the v3 converter's rules needs plain list and value operations, but nothing domain-specific.

- copy, a literal, a reference to a named value;
- value-list lookup (text to term, including a list entry's agreed encoding);
- split on a separator;
- regular-expression match, with a named group as the result;
- normalise a DOI or an e-mail address (the rules of lipidcompass-submissions `curation/identifiers.py`, written down in the mapping language's documentation; that file has no ORCID rule, so ORCID normalisation waits until one exists);
- unit conversion by a published factor table (nmol/mL → µmol/L);
- default value, first non-empty, conditional, equality and emptiness tests, lower case;
- string template;
- list operations:
  - find, filter;
  - map;
  - element at a position;
  - count;
  - concatenate;
  - the entries of an object;
  - an item's position;
- group aggregate (mean, standard deviation, coefficient of variation), for study-variable columns, with Python's arithmetic as the v3 converter computes them;
- join several values into one, as v3's two `sample_processing` entries do;
- render a value as a CV parameter, a user parameter, a label or a number.

The mapping language's documentation (`schema/mapping-language.md`) is the normative list.

**Named functions.** These are the documented set; an implementation must provide all of them to be conformant, except a reserved one.

- `goslin-parse`: a lipid name to its normalised name, formula and mass. The mass is the sum of the element masses with compensated (Neumaier) summation, as pygoslin computes it under Python 3.12 or later.
- `factor-combinations`: the study groups as the combinations of the primary factors' values, with their members, as the v3 converter forms them [added, evidence].
- `formula-check`: a stated sum formula checked against the parsed name, as the v3 converter does [added, evidence].
- `index-allocate` (§5.7): **reserved** (amended 2026-11-08, final N-1 review). It was never defined or implemented, and index allocation needs no function: `ref-to` names the element and the engine's applier gives it its index (§5.7). It is therefore not one of the functions required for vocabulary 1.0.0 conformance, and a mapping that calls it is refused when it is loaded. A later minor version of the mapping language may define it.

**Conformance examples.** Each mapping ships with pairs of an input and the mzTab-M lines it should produce. The v3 example workbook is the first; a checklist export is the second. An implementation that produces the same lines, compared after normalising the order of metadata lines, is conformant.

**Provenance.** The webapp is listed as `software[n]` with its version. Each mapping version that was used is recorded as a parameter of that software entry. **[verify]** that `mztab2mtbls` and LipidCompass accept them. If a parameter is not accepted, a `COM` line is used instead; it is listed as an allowed difference in the v3 equivalence test.

**Where they live [lead].**

- **Location:** a new top-level folder `submission-vocabulary/` in jmzTab-m-webapp. Its subfolders are `vocabulary/`, `lists/`, `mappings/input/`, `mappings/output/`, `schema/` (the mapping language) and `examples/`.
- **Licence [my call]:** CC-BY-4.0, the HUPO-PSI/mzTab-M licence, so they can move there without relicensing.
- **Publishing:** the webapp serves them read-only by version, and each release tag pins them.

### 5.4 Methods and protocols

**What the evidence says (§3):**

- MetaboLights receives methods only through `protocol[n]`. mztab2mtbls ignores 2.0 `sample_processing`.
- jmzTab-m implements `protocol[n]` in neither line.

**The vocabulary keeps v3's method fields** (N15). The output mappings group them as follows.

- **2.0:** two `sample_processing` entries, exactly as v3's converter writes them (`:200-204`).

  The editor tells the researcher once that MetaboLights will ask for protocols after conversion. That matches what the lead described for 2.0 conversions.
- **2.1, once jmzTab-m implements `protocol[n]` (N20):**
  - **Protocols.** Each is named and typed as mztab2mtbls expects (N19): Sample collection, Extraction, Chromatography, Mass spectrometry, Data transformation, Metabolite identification.
  - **Parameters.** They use the names in `parameter_name_map`, for example "chromatography column", "ionization type", "instrument class", "scan polarity".
  - **Protocol types** use the terms of mztab2mtbls' MTBLS263 fixture (EFO/CHMO), or MSIO as in the spec's example **[verify]** which, against the fixture.
  - **Assays reference protocols with `assay[n]-protocol_refs`**, as the field is named and as the fixture writes it, not as the spec's example writes it.
  - **Until jmzTab-m implements `protocol[n]`**, the 2.1 output mapping writes no protocols.

**Upstream work this needs (outside this repository):**

- **A jmzTab-m issue:** "2.1: implement `protocol[n]` and `assay[n]-protocol_refs`" (`MetadataElement`, OpenAPI model, parser, writer, validator).
- **A note on HUPO-PSI/mzTab-M#95:** the example contradicts the field name (`protocol_ref` vs `protocol_refs`).

Both are drafted for the lead to file.

### 5.5 Tables and data files

**Samples table.**

- **Formats:** CSV, TSV or the first sheet of an XLSX, or a sheet the researcher chooses. XLSX is read in the browser and sent as rows.
- **Column roles.** The researcher confirms each column's role:
  - sample name;
  - a vocabulary field (species, tissue, cell type, disease, sex, age, description);
  - a study factor;
  - a data file column;
  - "ignore".
- **Header synonyms.** The input mapping suggests roles from the column headers.
- **Values.** Each distinct value of a term column is matched against the field's value list, then against OLS within the allowed ontologies (§5.8). The researcher confirms each distinct value once, not each cell.
- **Study factors.** The researcher marks which factors define the study groups ("primary", as in v3). Study variables are the combinations of the primary factors' values that occur, as in v3.

**Data files.**

- **Choosing them.** The researcher chooses files, or a folder (`webkitdirectory`). Nothing is uploaded.
- **What the browser reads:**
  - each file's name and size;
  - for mzML, the header up to the first `<spectrum>`, plus that spectrum's polarity. The file is streamed, so it is never held whole.
- **Pairing.** Raw and mzML files are paired by base name.
- **Linking files to samples.** The editor proposes a sample for each file in this order:
  1. the samples table's data file column;
  2. an exact sample name in the file name;
  3. no proposal: the researcher links the file by hand.
- **Fractions.** Several files of one sample can be marked as fractions of one measurement. They then share one assay (N6).
- **`ms_run[n]-location` (N13).**
  - **With a repository base URL:** the base URL plus the file name. Examples are a MetaboLights `FILES/` URL or a LipidCompass storage URL.
  - **With only the file:** the file's base name, percent-encoded, as a relative URI reference (`M11050401.mzML`). This is not invented: it is the real file name.
    - It passes both jmzTab-m lines today.
    - It is allowed by HUPO-PSI/mzTab-M#154, which only says SHOULD be absolute.
    - It would get at most the proposed Warn-1040 (jmzTab-m#250).
    - mztab2mtbls turns it into exactly `FILES/M11050401.mzML`.
  - **With no data file known:** `null`, as both spec versions require. Until jmzTab-m#251 is fixed in both lines, this case is listed in "still missing", because the validator rejects it today. A fix in 1.0.x is a bug fix under the engines design's E3, since the 2.0 spec already mandates `null`.
  - **Same base name twice:** refused, because mztab2mtbls flattens locations to base names.
- **`ms_run[n]-scan_polarity`** comes from the mzML header, else from the dataset's scan polarity (§5.2).

**Summary table.**

- **Layout.** Samples in rows and features in columns, or the reverse, chosen as in v3. Row and column names are matched to the copy's assays or samples, and features by name.
- **Quantity type.** The researcher declares it once per table. It sets `quantification_method`, `small_molecule-quantification_unit` and v3's `data_processing_level`:
  - **absolute:** concentration or amount, with a unit from the unit list; unit conversions come from the published factor table;
  - **relative:** internal-standard-normalised ratio or mol%, with an optional unit;
  - **arbitrary:** raw peak area or intensity, with no unit, or the detector-counts term.

  The lists are v3's `quantification_method`, `quantification_unit` and `data_processing_level`.
- **Numbers.** Numbers are read with the decimal point. A table using a decimal comma is detected, and the researcher confirms it before any conversion.
- **Missing values.** An empty cell becomes `null`. Other encodings ("n.d.", "<LOD", 0) are listed, and the researcher chooses for each whether it becomes `null`, as v3's `missing_value_encoding`.
- **Features.**
  - Under the `lipid` extension, feature names go through `goslin-parse`.
  - Reliability defaults are as in v3 (1, 3 or 4), and each default is listed as a suggestion for the researcher to see.
  - Study-variable abundance and variation columns are computed by the aggregate transform, as v3 does.
- **Table caps.** Summary tables obey the working-copy caps (editor design §5.9).

**A tool's file already has features.** The summary table is then not imported; its numbers are already in the file.

### 5.6 Methods text and the language model

**Server side only.** The webapp server calls the endpoint configured in its settings: base URL, model name, token and timeouts. The browser never talks to the model.

- **Per deployment (N17).** No endpoint configured means "Add from the methods text" is not offered, and everything else works. The public validator ships without one.
- **Where enabled:**
  - inputs are capped at 50,000 characters [my call; a methods section is far shorter];
  - each browser session is rate-limited (5 requests per 10 minutes [my call]);
  - calls run on their own bounded executor.
- **Privacy:** before sending, the UI names the service the text goes to, for example "de.NBI Cloud Bielefeld" or "this site's own model service". It also says that the text is not stored there beyond the request. Nothing is sent until the researcher confirms.

**What the model is asked to do.**

- **The schema.** The `methods-text` input mapping lists the target vocabulary fields: v3's method fields, instrument, ion source, analyzer, polarity, extraction, separation, column, internal standards, software and quantification method. A JSON Schema is generated from that list.
- **Every value in the schema has two parts:**
  - a `value`, which is text or a term label;
  - an `evidence` field, holding the verbatim span of the methods text it came from.
- **No numbers.** The model never fills a numeric field, a date or a count.
- **How the schema is enforced.** The request uses vLLM structured outputs (`response_format` with `json_schema`). **[verify]** that the Bifrost gateway passes this through, and that reasoning models honour it on their final answer.

  If either fails, the server checks the reply against the schema itself and asks once more. It then gives up with a message, never a partial result.

**What the server checks before showing anything.**

- **The evidence.** It must occur in the submitted text, compared after normalising whitespace. A suggestion whose evidence is not found is dropped.
- **Term grounding:**
  1. OLS4 lexical search within the field's allowed ontologies;
  2. a subtree check against the allowed roots;
  3. the candidates ranked against the evidence sentence with `bge-reranker-v2-m3`, where configured.

  Only an exact label or synonym match is preselected; anything else is shown unselected, with its candidates.
- **The audit record.** It keeps the model name and the mapping version, in the provenance (§5.3).

**The default model [my call, to be measured].**

- `Qwen3.6-35B-A3B` on the gateway; on the L4, a model of up to about 30B parameters in int4, or 7–14B in FP8.
- The choice is made by measurement: an evaluation set of 20 or more LipidCompass datasets, with methods text and curated metadata. It reports correct, wrong and missing values per field. It runs on demand on `-dev`, not in CI.
- The model is a setting; the contract is the mapping.

### 5.7 Applying suggestions to the working copy

**The suggestion panel.**

- Each suggestion shows:
  - the target (a readable field name and the mzTab-M key);
  - the value (a term, with its label and accession, or text);
  - its source: the input, its row, column or field, or the quoted sentence;
  - its state (new, replaces existing, conflicts).
- Suggestions from tables, the checklist and mzML headers can be accepted in bulk after the preview.
- Suggestions from the methods text are accepted per group (one protocol, the instrument), after the quote has been seen.

**Applying (N7).**

- **What the engine does.** The webapp sends the engine three things: the copy's text, the accepted vocabulary values, and the output mapping version. The engine returns line edits, using only the operations the webapp has: `InsertLine`, `SetCell`, `InsertColumn`, `SetRawLine`, `DeleteLine`.
- **Index allocation.** The engine finds the existing `sample[n]`, `assay[n]`, `ms_run[n]`, `study_variable[n]` and `cv[n]` by name, and each group's last line. A new entry gets the next free index. An entry that matches an existing one by name updates it instead, shown as "replaces existing" in the preview.
- **CV declarations** follow the parameters: a CV is declared when a parameter uses it, and only then, as in the lipidcompass-submissions #213 fix.
- **Table columns.** `abundance_assay[n]` and study-variable columns are inserted with `InsertColumn` and `SetCell`, never by reformatting a section.
- **Undo.** The webapp applies the edits as one batch against the copy's current revision, so undo removes them all at once.
- **Stale suggestions.** If the copy changed since the preview, the batch is refused (409, as for all edits), and the preview is recomputed.

**Validation.** After applying, the copy is validated as after any edit (editor design §5.4). The "still missing" checklist (§5.1) is recomputed from the messages, the profile's rules and the vocabulary's requirement levels (N14).

### 5.8 Terms

Term pickers and value matching use:

- first, the vocabulary's value list for the field (fast, offline, ordered by use);
- then, where the engine exposes it (N18), the engine's CV suggestions for the chosen profile;
- otherwise OLS4 within the allowed ontologies.

A value with no term is kept as a user parameter, as v3 does. The repository rules decide whether that is a warning or an error.

### 5.9 A 2.0 file from a tool

Tools write 2.0 today. **[my call]** Upgrading 2.0 to 2.1 is out of this design. It needs its own design, once jmzTab-m 2.1 implements protocols: what moves (`-factors` to `study_variable_group`, `sample_processing` to `protocol`) and what the researcher must supply.

Until then, a tool's 2.0 file is completed as 2.0, with the 2.0 output mapping.

### 5.10 REST and engine

These are capabilities; the builder designs the shapes, following editor design §5.9's constraints: named SQL parameters, everything validated and constrained, and caps.

| Where | What |
|---|---|
| `POST /rest/v2/working-copies` | Gains "new file": version, content profile, repository rules. The copy has no validation record behind it; copy metadata stores its origin as `new`. The profile must list the version (E9). |
| engine | **Skeleton**, per version and content profile (N18). |
| engine | **Field rules** for a catalog profile (N18). |
| engine | **Apply output mapping:** the copy's text, vocabulary values and mapping version in; line edits and per-suggestion states out (N7). |
| engine (optional in N-2) | **CV suggestions** (editor design §5.8). |
| `GET /rest/v2/submission-vocabulary/…` | The vocabulary, lists, mappings and schema, by version. They are static files and can be cached. |
| `POST /rest/v2/working-copies/{id}/imports` | Input kind, input mapping version, and the input: rows, JSON, mzML header values, or text. The webapp runs the input mapping, then asks the engine for the output side. The response holds suggestions and their states against the current revision. |
| `POST /rest/v2/working-copies/{id}/imports/{importId}/apply` | The accepted suggestion ids and the revision. It applies one batch and returns the new revision. |
| settings | The model endpoint (base URL, model, token, timeouts, concurrency, input cap, rate limit), the reranker endpoint, and the OLS base URL. |

**Model calls** run on their own bounded executor, as working-copy runs do in the pre-deploy plan, Task 1. When it is full, the server answers 503 with `Retry-After`.

### 5.11 SPA

- **Navigation bar:** **New file**, at route `new`. It opens a short form:
  - version: the configured default, preselected, among the configured engines (N12);
  - content profile: 2.1 only, with a one-line meaning for each;
  - repository rules: the catalog profiles that list the version.
- **Editor:** an **Add from…** panel, with one entry per input kind, next to the messages panel. Its contents:
  - the import flows (§5.5, §5.6) with their previews;
  - the "still missing" checklist (§5.1).
- **Library.** These parts live in `projects/mztabm-editor` (editor design R10), so LipidCompass gets them with the package (phase 2c).

### 5.12 LipidCompass

In phase 2c, LipidCompass embeds the editor package, so that a submitter can create or fix a file in place (editor design §5.11).

**[my call]** The new-file flow replaces the wizard's form steps that this design covers:

- study, contacts and samples;
- methods;
- files;
- results.

The wizard keeps what is LipidCompass's own: account, licence and release.

LipidCompass reads 2.0 only (jmzTab-m 1.0.6), so files meant for it are 2.0 until LipidCompass moves to jmzTab-m 2.1 (engines design §5.9). Submitting the editor's file into a LipidCompass submission belongs to the LipidCompass integration's design.

---

## 6. Phasing [my call]

| Phase | Scope | Done when |
|---|---|---|
| N-1 | Vocabulary 1.0.0 (v3 unchanged plus the additive groups), lists, mapping language and schema, the 2.0 output mapping, the `v3-workbook` input mapping, conformance examples, and the engine operation "apply output mapping" for 2.0. The v3 workbook build reads the published vocabulary. | The 2.0 engine turns the v3 example into the converter's checked-in output, except for the listed differences. The rebuilt schema JSON equals the committed one apart from `template.vocabulary`, and the four filled v3 workbooks and the example read identically. |
| N-2 | **New file** (route, skeleton, field rules); the samples table; data files and mzML headers; the summary table; suggestions and apply. The 2.1 output mapping without protocols. | A 2.0 file made from a samples table, data file names and a summary table has no errors under the default 2.0 profile, imports into a LipidCompass-equivalent parser (jmzTab-m 1.0.x), and converts with mztab2mtbls, passing its OPA validation with no errors. On `-dev`, the same holds for 2.1 under the default 2.1 profile. |
| N-3 | Lipidomics Checklist import (`checklist` form). | The checklist's conformance example passes. |
| N-4 | Methods text and the language model, on `-dev`. | The evaluation set shows the per-field results, and the lead accepts the default model and the deployment terms. |
| later | 2.1 protocols (after jmzTab-m implements them, N20); 2.0 → 2.1 upgrade (§5.9); several datasets at once; checklist sample sets and lipid classes; LipidCompass embedding (2c); native MetaboLights. | — |

**What each phase depends on:**

- **N-2** depends on the pre-deploy plan, Task 1's executor.
- **N-1 and N-2** need no jmzTab-m release.
- **`null` locations** wait for jmzTab-m#251.
- **2.1 protocols** wait for the new jmzTab-m issue (§5.4).

---

## 7. Documentation

- **The vocabulary and mapping language** get a reference page, generated from the JSON Schema and the vocabulary, in the docs area (docs design). The examples are part of it.
- **A how-to: "Make a file for MetaboLights"**, from New file to `mztab2mtbls`. It says plainly which methods reach MetaboLights for 2.0 and 2.1 today.
- **A how-to: "Use the mappings in your tool"**, with the conformance examples.
- **The README in `submission-vocabulary/`** gives the licence, versioning and how to propose changes.

## 8. Testing

- **Mapping language:** every mapping file validates against the schema (unit test).
- **Requirement sources:** the N14 check. No vocabulary requirement level may disagree with a profile rule on the same path.
- **v3 equivalence.** The v3 example workbook goes through the `v3-workbook` input mapping and the 2.0 output mapping in the 2.0 engine. The result must equal a **checked-in golden output** of `convert_submission_template.py`, after normalising line order. The allowed differences are listed next to the test:
  - `ms_run[n]-location` (N13);
  - the provenance entries (§5.3);
  - converter notes (conv stderr) are not compared; the engine and the mapping's functions give their own notes.

  The golden output is refreshed when the converter changes. Both outputs validate with jmzTab-m with no errors.
- **v3 reading:** the rebuilt schema JSON equals the committed one (as parsed JSON) apart from `template.vocabulary`, and it reads the four filled workbooks and the example with output identical to today's reader; the rebuilt example differs only in `template.generated`.
- **Index allocation (engine):**
  - imports into a copy that already has samples, assays and CVs: matching names update, new names get the next index, nothing is renumbered;
  - a stale revision is refused (webapp).
- **mzML header reader:** fixture headers from Thermo, Sciex, Bruker and Agilent conversions, including gzipped and indexed mzML. Only the header is read; a test checks that the file is not read to the end.
- **Locations:** a base name with spaces and non-ASCII characters is percent-encoded; duplicate base names are refused; mztab2mtbls' basename rule gives the original name back.
- **Language model:** the client is tested against a recorded endpoint. Evidence checks, schema rejection, the single retry, the input cap and the rate limit are tested with crafted replies. The evaluation set runs on demand.
- **End to end (N-2):** New file → samples → files → summary table → valid → download → mztab2mtbls with its OPA validation. It runs in CI if mztab2mtbls can run there (Python 3.13 with `uv`, OPA bundle) **[verify]**; otherwise as a documented manual check.

## 9. Risks

- **jmzTab-m lacks `protocol[n]`.** Without it, the main reason for 2.1 (protocols reaching MetaboLights) does not hold, and neither 2.0 nor 2.1 files carry methods into MetaboLights. N20 keeps this design from writing what the validator rejects. The upstream issue is the fix.
- **Mapping-language creep.** Every special case wants a transform. The fixed set (§5.3) and the rule "anything else belongs in a tool" are the defence. Changes to the set are a major vocabulary version.
- **Thin repository rules.** The MetaboLights profile is 2.1-only and old (rules to 2020), and the LipidCompass profile is unfinished. "Done when" therefore relies on the default profiles and on mztab2mtbls' own OPA validation (N14, §6).
- **Gateway behaviour and terms.** Structured outputs, rate limits, and terms of use for sending submitters' unpublished text under one service token. N17 keeps the public validator out of this until the terms are clear.
- **Java mapping and Python converter drifting after N-1.** The golden file catches this only when it is refreshed. Adopting the mappings in lipidcompass-submissions removes the risk; that is its decision.
- **Non-lipid features.** Goslin applies only under the lipid extension. Other names stay as written, with reliability 4 unless given.

## 10. For the lead to know (not blocking)

- **No jmzTab-m line implements 2.1 `protocol[n]`,** and mztab2mtbls ignores 2.0 `sample_processing`. So today no file carries methods into MetaboLights through the converter. Two drafts are ready to file:
  - a jmzTab-m issue for `protocol[n]` and `assay[n]-protocol_refs`;
  - a note on HUPO-PSI/mzTab-M#95 about the example that contradicts the field name.
- **de.NBI's terms** for a public service sending submitters' text under one service token decide whether the public validator ever enables methods text (N17).

## 11. Left to the builder

- The concrete YAML shape of the vocabulary, lists and mappings, within §5.2–§5.3.
- The JSON Schema of the mapping language.
- The engine-side representation of the copy's existing elements for index allocation (N7).
- The REST shapes (§5.10).
- XLSX reading in the browser (library choice).
- The UI layout of the Add from… panel and the suggestion preview.
- Header synonym lists for the table imports.
