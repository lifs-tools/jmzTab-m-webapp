# Submission vocabulary

The submission vocabulary is the public field dictionary for describing a lipidomics study: the fields of each group (study, contacts, datasets, samples, factors, lipids, quantities, runs), their labels, help texts, types and requirement levels, and the value lists they draw from. It makes the LipidCompass submission template v3 usable by any tool that wants to read or write the same fields, and it is the fixed point that input and output mappings (for example to mzTab-M) refer to.

## Contents

| Path | What it holds |
|---|---|
| `vocabulary/vocabulary.yaml` | The groups and their fields, and the conventions of the workbook layout. |
| `lists/<list>.yaml` | One file per value list, named exactly as the list key a field refers to. An entry is a CV term (`cv`, `accession`, `label`) or a fixed text (`text`, optional `note`, optional `encoding`). Order is the order of preference. |
| `lists/unit-factors.yaml` | The unit conversion factors of the mapping language's `convert-unit` form. Not a value list. |
| `schema/*.schema.json` | JSON Schemas (draft 2020-12) for the vocabulary file, the list files and the mapping files. |
| `schema/mapping-language.md` | The mapping language: its rules and expression forms. It is normative. |
| `mappings/` | Mapping files that connect an input or an output format to the vocabulary (`mappings/input/<id>.yaml`, `mappings/output/<id>.yaml`). |
| `examples/` | Example instances. |

Groups with `workbook: true` are rendered in the v3 workbook exactly as before. The group `runs` is optional and is not rendered there: it describes one data file per row (file name, format, paired raw or mzML file, sample, assay, fraction, and polarity, instrument and fragmentation from the mzML header). Dataset-level fields stay the fallback for run-level ones.

A text entry can carry an agreed `encoding`, the mzTab-M parameter it is written as. For example, "mol% (mole percent of the measured lipidome)" is written as `[UO, UO:0000076, mole fraction, mol%]`.

## Versioning

The vocabulary uses semantic versioning (`vocabulary.version`):

- removing or renaming a field id, or removing a list or an entry, is a major change;
- adding a field, a list or a list entry is a minor change;
- fixing a label or help text is a patch.

Version 1.0.0 is the field dictionary of lipidcompass-submission-template 3.0.0 (lipidcompass-submissions commit bb8dcbf), moved without changing any field key, plus the `runs` group. Only the template's own identity (id, version, title, contact) is not part of the vocabulary.

## Using it from Java

```java
Vocabulary v = Vocabulary.load();
v.version();                          // "1.0.0"
v.group("study").fields();            // the fields of the Study sheet
v.list("species").entries();          // CV terms and texts
v.encodingOf("mol% (mole percent of the measured lipidome)");

Mapping m = MappingLoader.load("mztab-2.0.0-M");   // validated against schema/mapping.schema.json
Evaluator e = new Evaluator(m.let(), v, functions);
Scope s = Scope.root(instance, Map.of("study_id", "LCS-00123"), new Notes());
e.metadata(m.metadata(), s);                       // the metadata lines, before rendering
```

## Licence

The vocabulary, its lists and schemas are released under the Creative Commons Attribution 4.0 International licence (CC-BY-4.0). See `LICENSE`.

## Proposing changes

Open an issue or a pull request in the jmzTab-m-webapp repository that changes the files here. Say which fields or list entries change and why, and which version bump follows from the rules above. Every change must keep the schemas satisfied; the test `VocabularyFilesTest` validates all files against them.
