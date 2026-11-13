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

Groups with `workbook: true` are rendered in the v3 workbook with the same sheets and fields as before; only the list sheets are now ordered by list name. The group `runs` is optional and is not rendered there: it describes one data file per row (file name, format, paired raw or mzML file, sample, assay, fraction, and polarity, instrument and fragmentation from the mzML header). Dataset-level fields stay the fallback for run-level ones.

A text entry can carry an agreed `encoding`, the mzTab-M parameter it is written as. For example, "mol% (mole percent of the measured lipidome)" is written as `[UO, UO:0000076, mole fraction, mol%]`.

## Versioning

The vocabulary uses semantic versioning (`vocabulary.version`):

- removing or renaming a field id, or removing a list or an entry, is a major change;
- adding a field, a list or a list entry is a minor change;
- fixing a label or help text is a patch.

Version 1.0.0 is the field dictionary of lipidcompass-submission-template 3.0.0 (lipidcompass-submissions commit bb8dcbf), moved without changing any field key, plus the `runs` group. Only the template's own identity (id, version, title, contact) is not part of the vocabulary. The `mztab` hint of `lipids.internal_standard` lost an internal note.

Version 1.1.0 adds seven entries to `quantification_unit`, the CV units that published LipidCompass datasets use: Nanomole per Gram of Dry Weight (`NCIT:C208461`), Millimole per Kilogram (`NCIT:C68892`), Milligram per Kilogram (`NCIT:C67401`, µg/g), Microgram per Kilogram (`NCIT:C67396`, ng/g), Micromole (`NCIT:C48509`), mole fraction (`UO:0000076`, values 0..1) and Micromole per Mole (`NCIT:C122231`). No field or entry changes.

Version 1.2.0 adds the optional field `lipids.alternative_name` (column 'Alternative name' on the Lipids sheet): a second name for the same lipid, kept as written, for example a curator's reading with more detail than the lipid name states. It is written to mzTab-M as `opt_global_alternative_name`; the lipid name stays the one that is normalised. The v3-workbook and mztab-2.0.0-M mappings read and write it and are version 1.1.0.

Version 1.3.0 adds the optional field `lipids.normalise_name` (column 'Normalise the name', `yes_no`): 'no' keeps a name that Goslin reads as a different lipid as written and not identified, like a name Goslin cannot parse. The mapping language gains the function `strip-name-notes`: the mztab-2.0.0-M mapping (1.2.0) reads a name that does not parse again without the custom suffixes and comments at its end, so the name as written stays in `opt_global_original_name`. The v3-workbook mapping is 1.2.0.

The mztab-2.0.0-M mapping 1.3.0 writes `goslin:null` (the database prefix followed by null) as the database identifier of a name Goslin does not read or that is not normalised, instead of `null`. `chemical_name` holds the name, and jmzTab-m 1.0.8 requires as many identifiers as names (Error-2046). The vocabulary is unchanged.

The `mztab` hint of a field says where the field usually ends up in mzTab-M. It is informative: what is written is defined by the output mapping, which is normative.

## Getting it

- **Maven:** `org.lifs-tools:mztab-validator-submission-vocabulary`, in the version of the jmzTab-m-webapp release. The jar holds the files under `submission-vocabulary/` and the Java loader.
- **HTTP:** a running webapp serves the files of the vocabulary version it carries, read-only, at `/rest/v2/submission-vocabulary/{version}/{path}`, for example `/rest/v2/submission-vocabulary/1.0.0/vocabulary/vocabulary.yaml`. `{path}` is `README.md`, `LICENSE` or a file below `vocabulary/`, `lists/`, `mappings/input/`, `mappings/output/`, `schema/` or `examples/`.

## Using it from Java

```java
Vocabulary v = Vocabulary.load();
v.version();                          // "1.0.0"
v.group("study").fields();            // the fields of the Study sheet
v.list("species").entries();          // CV terms and texts
v.encodingOf("mol% (mole percent of the measured lipidome)");

Mapping m = MappingLoader.load("mztab-2.0.0-M");   // validated against schema/mapping.schema.json
Evaluator e = new Evaluator(m.let(), v, new Functions());   // org.lifstools.mztab.submission.mapping.functions
Scope s = Scope.root(instance, Map.of("study_id", "LCS-00123", "dataset_index", "1"), new Notes());
e.metadata(m.metadata(), s);                       // the metadata lines, before rendering
```

## Licence

The vocabulary, its lists, schemas, mappings and examples (everything under `vocabulary/`, `lists/`, `schema/`, `mappings/` and `examples/`, and this README) are released under the Creative Commons Attribution 4.0 International licence (CC-BY-4.0). See `LICENSE`.

The Java code of this module (`src/`) is released under the Apache License, Version 2.0, as the rest of jmzTab-m-webapp.

## Proposing changes

Open an issue or a pull request in the jmzTab-m-webapp repository that changes the files here. Say which fields or list entries change and why, and which version bump follows from the rules above. Every change must keep the schemas satisfied; the test `VocabularyFilesTest` validates all files against them.
