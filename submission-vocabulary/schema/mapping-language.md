# The mapping language

This document is normative. A mapping file connects an input format to the submission vocabulary (an input mapping) or the vocabulary to an output format (an output mapping). It is plain YAML. `mapping.schema.json` (JSON Schema draft 2020-12) describes its shape, and this document defines what it means.

The language has only the expression forms and rule kinds listed here, and only the named functions listed under [`fn`](#fn). Adding either is a change of the language and of its documentation.

Python behaviour is cited from lipidcompass-submissions commit bb8dcbf: `conv:NNN` is a line of `tools/convert_submission_template.py`, and `identifiers.py` is `src/curation/identifiers.py`. Python's numbers are those of CPython 3.12 or later.

## Contents

- [Files](#files)
- [Values](#values)
- [Rules](#rules): [line](#line), [each](#each), [table](#table), [column](#column), [each column](#each-column)
- [Expressions](#expressions)
- [Cell rendering](#cell-rendering)
- [Errors](#errors)

## Files

A mapping lives in `mappings/input/<id>.yaml` or `mappings/output/<id>.yaml`, where `<id>` is its `id`. Its top-level keys are:

| Key | Required | Meaning |
|---|---|---|
| `id` | yes | The mapping id, e.g. `mztab-2.0.0-M`: letters, digits, `.` and `-`, starting with a letter or a digit. |
| `version` | yes | The mapping's version, `major.minor.patch`. |
| `kind` | yes | `input` or `output`. |
| `vocabulary` | yes | The vocabulary version the mapping reads or writes, e.g. `1.0.0`. |
| `target` | output only | The mzTab-M version an output mapping writes, e.g. `2.0.0-M`. An input mapping has none. |
| `parameters` | no | The names of the parameters a run is given, e.g. `study_id`. Expressions read them as `params.<name>`. |
| `cvs` | no | The CVs the mapping's parameters use, by label, each with `full-name`, `version` and `uri`. Their order is the order in which they are declared. |
| `let` | no | Named values: name to expression. A name never shadows: it is not `params`, not a top-level key of the vocabulary instance (`template`, `study`, `contacts`, `datasets`, `samples`, `factors`, `lipids`, `issues`) and not a vocabulary group id (`quantities`, `runs`). |
| `metadata` | output only | Metadata rules. |
| `tables` | output only | Table rules. |

The body of an input mapping is not defined yet: an input mapping has only the keys common to both kinds.

```yaml
id: mztab-2.0.0-M
version: 1.0.0
kind: output
vocabulary: 1.0.0
target: 2.0.0-M
parameters: [study_id, dataset_id, dataset_index]
cvs:
  NCBITaxon: {full-name: NCBI organismal classification, version: "2026-07-12", uri: "http://purl.obolibrary.org/obo/ncbitaxon.owl"}
let:
  dataset: {find: {in: {field: datasets}, as: d, where: {eq: [{field: d.dataset_id}, {field: params.dataset_id}]}}}
metadata:
  - key: mzTab-version
    value: {literal: 2.0.0-M}
```

A YAML key must not appear twice in one node.

## Values

An expression evaluates to one of these values:

| Value | Holds |
|---|---|
| Null | Nothing: JSON `null`, a missing field, a failed lookup. |
| Text | A text. |
| Int | An integer (64 bits), from a JSON integer. |
| Dbl | A float (IEEE double), from a JSON float or from arithmetic. |
| Obj | An object: keys to values, in order. |
| List | A list of values. |
| Param | A parameter: cv, accession, name and value. |
| Ref | A reference to an element of the output by kind and name, e.g. the sample called `P001`. The engine that writes the output decides its index. |

The instance is the vocabulary instance as JSON (design §5.2). JSON integers stay integers and JSON floats stay floats: `45` is an Int and `45.0` a Dbl. A JSON boolean becomes the text `true` or `false`.

**Empty.** Null, the text `""`, an empty list and an empty object are empty. Every other value is not empty; numbers, 0 included, are never empty.

**Conditions.** A condition (`when`'s `if`, every `where`) holds when its value is not empty. The tests [`eq`](#eq) and [`not-empty`](#not-empty) give the text `true` when they hold and Null when they do not.

**As text.** Where a form needs a text, a value is read as Python's `str()` would write it: a text is itself, an Int is its digits, a Dbl is written as in [`number`](#number), a Param is its [rendered cell](#cell-rendering) (or `""` when it renders as nothing), and Null is `""`. An object, a list or a reference is an error there.

**Positions.** `each`, `map`, `find` and `filter` bind each item of a list to a variable (`as`). The item's position is its 1-based index in that list. An `each` binds every item, with its position, before its `where` is evaluated, so the position counts the items its `where` drops as well. A key's `{x#}` numbers only the items that wrote a line (see [line](#line)).

## Rules

### line

A line is one metadata line, a `key` and a `value`. A line whose value is empty is not written (`conv:265`).

```yaml
- key: mzTab-version
  value: {literal: 2.0.0-M}
```

**Key templates.** Inside an `each`, a key may hold `{x#}`, where `x` is the variable of an enclosing `each`. It is the 1-based position of `x`'s item among the items of that `each` that produced at least one written line. Items that produce no line are not counted, which is how the converter numbers after filtering. A key holds no other `{…}`.

### each

`each` binds every item of a list to `as`, with its position in that list, and evaluates `where` (optional) with it. For the items for which `where` holds, or for every item without a `where`, it writes `lines`. `lines` may hold lines and further `each` rules. An item that `where` drops keeps its place in the positions of the others, but writes nothing and takes no `{x#}` number.

```yaml
- each: {field: contacts}
  as: c
  lines:
    - key: "contact[{c#}]-name"
      value: {field: c.name}
    - key: "contact[{c#}]-email"
      value: {normalise: {kind: email, value: {field: c.email}}}
```

In the following, a contact with neither a name nor an e-mail writes nothing and takes no number. A nested `each` numbers its own items again for each item of the outer one:

```yaml
- each: {field: samples}
  as: s
  lines:
    - key: "sample[{s#}]"
      value: {field: s.sample_name}
    - each: {entries: {field: s.factors}}
      as: f
      lines:
        - key: "sample[{s#}]-custom[{f#}]"
          value: {user-param: {name: {field: f.key}, value: {label: {field: f.value}}}}
```

### table

A table rule writes a table section: `section` is the row prefix (e.g. `SML`) and `header` the header prefix (e.g. `SMH`). There is one row for every item of `rows`, bound to `as`. `columns` lists columns and column groups (`each`), in order.

```yaml
tables:
  - section: SML
    header: SMH
    rows: {field: dataset.quantities.lipids}
    as: lipid
    columns:
      - header: SML_ID
        value: {pos: lipid}
      - header: chemical_name
        value: {field: lipid}
```

### column

A column has a `header` (its name), a `value` (evaluated once per row, with the row variable bound) and an optional `where`. `where` is evaluated once for the table, without the row variable. When it does not hold, the column is left out of the header and of every row.

```yaml
- header: opt_global_internal_standard
  value: {when: {if: {eq: [{field: lipid_note.internal_standard.text}, {literal: "yes"}]}, then: {literal: "true"}, else: {literal: "false"}}}
  where: {find: {in: {ref: dataset_lipids}, as: l, where: {not-empty: {field: l.internal_standard}}}}
```

### each column

An `each` in `columns` writes its `columns` for every item of a list, bound to `as`. Each inner column's `where` is evaluated once per item, without the row variable. A header may hold `{x#}`, the 1-based position of `x`'s item among the items that kept at least one column.

```yaml
- each: {field: dataset.quantities.samples}
  as: assay
  columns:
    - header: "abundance_assay[{assay#}]"
      value: {number: {at: {list: {at: {list: {field: dataset.quantities.values}, index: {pos: assay}}}, index: {pos: lipid}}}}
```

## Expressions

An expression is a YAML object with exactly one key, its form. Expressions nest.

### literal

A fixed value, written as YAML. Booleans are not allowed: YAML reads an unquoted `yes`, `no`, `true` or `false` as a boolean, so quote them to mean the text.

```yaml
{literal: 2.0.0-M}          # Text "2.0.0-M"
{literal: 3}                # Int 3
{literal: "yes"}            # Text "yes"
{literal: [{text: a}]}      # List of one Obj
```

### field

A path, `a.b.c`. The first segment is, in this order:

1. a variable bound by an enclosing form, innermost first;
2. a [`let`](#ref) name (which never equals `params` or an instance key);
3. `params`, the run's parameters, each a text (absent ones are Null);
4. a top-level key of the instance.

Each further segment reads that key of an object. A missing key, or a segment applied to anything other than an object, gives Null.

```yaml
{field: study.study_title}
{field: params.study_id}
{field: s.sample_type.name}
```

### ref

The value of a `let` name. It is evaluated once per run, in the scope of the whole run, so it sees no variables. A name that refers to itself, directly or through other names, is an error. A [`field`](#field) path may also start with a `let` name, to read into its value.

```yaml
let:
  dataset: {at: {list: {field: datasets}, index: {field: params.dataset_index}}}
  samples_of_dataset: {field: dataset.quantities.samples}
metadata:
  - key: title
    value: {format: {template: "{0}: {1}", args: [{field: study.study_title}, {field: dataset.dataset_title}]}}
  - key: custom[1]
    value: {user-param: {name: {literal: number of samples}, value: {count: {ref: samples_of_dataset}}}}
```

### pos

The position of a variable's item: an Int, 1-based (see [Values](#values)).

```yaml
{map: {over: {literal: [a, b]}, as: x, value: {pos: x}}}    # [1, 2]
```

### format

A text from a `template` and its `args`. `{n}` is argument `n` (0-based) as text. `{n:w}` pads an integer to width `w` with spaces, and `{n:0w}` with zeros after the sign, as Python's `format(n, "0w")`. A text that is an integer counts as one, which is how parameters are given. `{{` and `}}` are braces.

```yaml
{format: {template: "{0}-{1:02}", args: [{field: params.study_id}, {field: params.dataset_index}]}}   # "LCS-99999-01"
```

### join

Joins `items` with the text `sep`. `items` is a list of expressions or one expression. A list among the values is spread into its items. Each item is read as text; a Param is its rendered cell. Empty items (and parameters that render as nothing) are skipped. A reference cannot be joined: give the list of references as the line's value instead (see [Cell rendering](#cell-rendering)).

```yaml
{join: {items: [{literal: a}, {literal: null}, {literal: b}], sep: "|"}}     # "a|b"
{join: {items: [{term: {value: {field: d.extraction_method}}},
                {when: {if: {field: d.sample_preparation},
                        then: {user-param: {name: {literal: sample preparation}, value: {field: d.sample_preparation}}}}}],
        sep: "|"}}
```

### first

The first item that is not empty, or Null.

```yaml
{first: [{field: d.raw_data_location}, {at: {list: {split: {value: {field: study.repository_links}, sep: ";"}}, index: {literal: 1}}}]}
```

### split

Splits the text of `value` at `sep`. Each part is stripped of whitespace, and empty parts are dropped. Null gives the empty list. This is `conv:148`.

```yaml
{split: {value: {literal: " a; b ;; c "}, sep: ";"}}     # ["a", "b", "c"]
```

### when

`then` when `if` holds, else `else`. Without `else`, the result is Null.

```yaml
{when: {if: {field: d.analyzer}, then: {term: {value: {field: d.analyzer}}}}}
```

### eq

Compares two values as text, case-sensitively. It gives the text `true` or Null. Null and `""` are equal.

```yaml
{eq: [{field: f.primary.text}, {literal: "yes"}]}
```

### lower

The text in lower case. Null stays Null.

```yaml
{lower: {field: study.publication_doi}}
```

### not-empty

The text `true` when the value is not empty (see [Values](#values)), else Null.

```yaml
{not-empty: {field: c.email}}
```

### count

The number of items of a list, as an Int. Null counts as 0.

```yaml
{count: {field: dataset.quantities.samples}}
```

### at

The item at a 1-based `index` (an Int, or a text that is one), or Null when the index is out of range. A Null list gives Null.

```yaml
{at: {list: {field: datasets}, index: {field: params.dataset_index}}}
```

### concat

The lists joined into one. Null counts as the empty list.

```yaml
{concat: [{literal: [a]}, {literal: [b, c]}]}     # [a, b, c]
```

### entries

An object as a list of `{key, value}` objects, in order. Null gives the empty list.

```yaml
{entries: {field: s.factors}}     # {"BMI": 29.7} gives [{key: "BMI", value: 29.7}]
```

### map

`value` for each item of `over`, with the item bound to `as`. The result is a list.

```yaml
{map: {over: {field: dataset.quantities.samples}, as: x, value: {ref-to: {element: assay, name: {field: x}}}}}
```

### find

The first item of `in` for which `where` holds, with the item bound to `as`, or Null.

```yaml
{find: {in: {field: samples}, as: s, where: {eq: [{field: s.sample_name}, {field: name}]}}}
```

### filter

Every item of `in` for which `where` holds, with the item bound to `as`.

```yaml
{filter: {in: {field: lipids}, as: l, where: {eq: [{field: l.dataset_id}, {field: params.dataset_id}]}}}
```

### term

A value as an mzTab-M parameter, as `conv:129-139`. `param-value` (optional, as text) is the parameter's value.

- Null and `""` give Null.
- An object with a non-empty `accession` gives the CV parameter `[cv, accession, name, param-value]`.
- Otherwise the text is the object's `text`, or the value as text. When a vocabulary list entry with that text has an agreed `encoding`, the result is that encoding, and `param-value` is not used. Otherwise it is the user parameter `[, , text, param-value]`.
- Any of these parameters that would render as nothing (no accession and an empty name), an agreed encoding included, gives Null.

```yaml
{term: {value: {field: d.quantification_unit}}}
# {"text": "mol% (mole percent of the measured lipidome)"} gives [UO, UO:0000076, mole fraction, mol%]
# {"text": "Lipidome study"} gives [, , Lipidome study, ]
{term: {value: {field: d.processing_software}, param-value: {field: d.processing_software_version}}}
```

### cvparam

A CV parameter from its parts, each read as text; `value` is optional. A parameter without an accession and a name gives Null.

```yaml
{cvparam: {cv: {literal: NCIT}, accession: {literal: "NCIT:C28421"}, name: {literal: Sex}, value: {label: {field: s.sex}}}}
```

### user-param

A user parameter `[, , name, value]`; `value` is optional. An empty name gives Null.

```yaml
{user-param: {name: {literal: chromatography column}, value: {field: d.column}}}
```

### label

As `conv:141`:

- for an object, its `name`, or its `text` when the name is empty;
- otherwise as [`number`](#number).

```yaml
{label: {field: s.disease}}     # {"cv": "DOID", "accession": "DOID:9352", "name": "type 2 diabetes mellitus"} gives "type 2 diabetes mellitus"
{label: {literal: 45}}          # "45"
```

### number

A number as Python's `str()` writes it (`conv:145`), as a text. Null gives the text `null`.

- An Int is its digits.
- A Dbl is the shortest decimal that reads back as the same double (the closest one when there are several). It is positional when the decimal exponent is from -4 to 15, with a trailing `.0` for integral values. Otherwise it is scientific, with Python's style: a mantissa without a trailing `.0`, `e`, a sign and at least two exponent digits.
- A text is itself.

```yaml
{number: {literal: 29.7}}      # "29.7"
{number: {literal: 45.0}}      # "45.0"
{number: {literal: 1.0e-5}}    # "1e-05"
{number: {literal: 1.0e16}}    # "1e+16"
{number: {literal: 1.0e15}}    # "1000000000000000.0"
{number: {literal: null}}      # "null"
```

### normalise

Checks a DOI or an e-mail address with the rules of `identifiers.py`. An empty value gives Null without a note. A value that fails the check gives Null and a note: `'<value>' is not a DOI` or `'<value>' is not an e-mail address`.

- `kind: doi` (`mztab_doi`) does the following:
  1. It strips the value.
  2. It takes the first match of `10\.\d{4,}/\S+`. Without a match, it removes a leading `doi:`, `http://doi.org/`, `https://doi.org/`, `http://dx.doi.org/` or `https://dx.doi.org/` (ignoring case) and strips again.
  3. It removes trailing characters among ``.,;:)]}'"/``.
  4. The result is the DOI when all of it matches `10\.\d{4,}/\S+` and it holds no `:`, since jmzTab-m splits a publication item at `:`.
- `kind: email` (`valid_email`): the value, unchanged, when the stripped value matches all of jmzTab-m's `REGEX_EMAIL`, `[_A-Za-z0-9-]+(\.[_A-Za-z0-9-']+)*@[A-Za-z0-9]+(?:[-.][A-Za-z0-9]+)*(\.[A-Za-z]{2,})`.

`\d` is any Unicode decimal digit. Whitespace (`\s`, stripping) is Python's: the characters for which `str.isspace()` is true.

```yaml
{normalise: {kind: doi, value: {field: study.publication_doi}}}     # "https://doi.org/10.1234/abc.5" gives "10.1234/abc.5"
{normalise: {kind: email, value: {field: c.email}}}
```

### match

The named `group` of the first match of `regex` anywhere in the text of `value`, or Null when there is no match or the group did not take part. Null gives Null. Anchor with `^` and `$` to match the whole text. The regex uses the syntax common to Java and ECMAScript, and named groups are written `(?<name>…)`.

```yaml
{match: {value: {field: params.study_id}, regex: "^LCS-(?<n>\\d{5})$", group: n}}
```

### convert-unit

Converts a number from the unit `from` to the unit `to`. The units are values read as [`label`](#label), so a text or a CV term. Null gives Null. When the units are the same, the number is unchanged. Otherwise the number is multiplied by the factor in `lists/unit-factors.yaml`; an Int times an integer factor stays an Int, as in Python. When there is no factor, the result is Null and the note `no factor converts <from> to <to>`.

In vocabulary 1.0.0 the factors are nmol/mL to µmol/L and pmol/mL to nmol/L, both 1.

```yaml
{convert-unit: {value: {field: v}, from: {literal: nmol/mL}, to: {literal: µmol/L}}}
```

### aggregate

The mean (`fn: mean`) or the coefficient of variation (`fn: cv`) of the numbers in the list `values`. Null items are left out, and any other non-number is an error. The arithmetic is Python's, as `conv:375-377`:

- `mean` is `statistics.fmean`: the correctly rounded sum (`math.fsum`) divided by the count. It is Null when there are no numbers.
- `cv` is `statistics.stdev / mean`. `stdev` is the square root, correctly rounded, of the exact sample variance of the numbers. It is Null for fewer than two numbers or a zero mean.

Results are always Dbl.

```yaml
{aggregate: {fn: mean, values: {literal: [2, 2]}}}         # 2.0
{aggregate: {fn: cv, values: {literal: [1.0, 3.0]}}}       # 0.7071067811865476
{aggregate: {fn: cv, values: {literal: [5.0, null]}}}      # Null
```

### ref-to

A reference to the output element of kind `element` (lower case letters and `_`, e.g. `sample`, `assay`, `ms_run`, `study_variable`) called `name`. The engine that writes the output resolves it to that element's index, and every reference to the same element gets the same index.

```yaml
{ref-to: {element: sample, name: {field: x}}}
```

### fn

A named function with `args`. The functions are exactly these:

- [`goslin-parse`](#goslin-parse);
- [`factor-combinations`](#factor-combinations);
- [`formula-check`](#formula-check);
- `index-allocate`.

Each is defined in its own section of this document when it is implemented. An implementation must provide all of them to be conformant. Functions may add notes. A call with the wrong number of arguments is an error.

```yaml
{fn: {name: goslin-parse, args: [{field: lipid}]}}
```

#### goslin-parse

`args: [name]`. The lipid name as Goslin reads it (`conv:162-167`, `conv:291-296`), an Obj with these members:

- `normalised` (Text): Goslin's lipid string, e.g. `PC 15:0_18:1[M[2]H7]` for `PC 15:0-18:1(d7)`.
- `formula` (Text or Null): Goslin's sum formula when it is plain, that is, when all of it matches `(?:[A-Z][a-z]?\d*)+`. A formula with heavy isotopes, such as `C41H73NO8PH'7`, gives Null.
- `mass` (Dbl): the mass as pygoslin's `LipidAdduct.get_mass` computes it. The terms are the mass of each element times its count, including the adduct's elements, and they are added in Goslin's element order (C, C13, H, H2, N, N15, O, O17, O18, P, P32, S, S34, S33, F, Cl, Br, I, As) with Python's builtin `sum`, which is Neumaier's compensated summation. With an adduct of charge z ≠ 0, the mass is then `(sum - z × 0.00054857990946) / |z|`. jgoslin's `getMass()` adds plainly and can differ in the last digit, so it is not used.

When no Goslin grammar reads the name, the result is Null, without a note. Null gives Null. Any other value than a text is an error.

```yaml
{fn: {name: goslin-parse, args: [{literal: PC 34:1}]}}   # {normalised: PC 34:1, formula: C42H82NO8P, mass: 759.57780591}
```

#### factor-combinations

`args: [names, samples, factors]`. The study groups of a dataset, as `Converter.study_variables` (`conv:333-340`) forms them:

- `names` is the dataset's list of sample names, in assay order.
- `samples` is the list of sample records, each found by its `sample_name`. The last record of a name counts (`conv:156`). An Obj of the records keyed by name is accepted as well.
- `factors` is the list of factor records. The primary factors are those whose `primary.text` is `yes`, in list order.

The value of a factor for a sample is read as `factor_value` reads it (`conv:169-173`). For `sex`, `age` and `disease`, it is the sample's own field of that name. For every other factor, it is the first entry of the sample's `factors` object whose key equals the factor name, ignoring case. A value that Python takes as false (Null, `""`, an empty list or object, 0 or 0.0) becomes `{text: not specified}`.

The result is a List with one Obj per combination of the primary factors' values, in order of first appearance among the names:

- `key` is a List of `{factor, value}`, one per primary factor: the factor name as the record gives it, and the sample's value.
- `members` is the List of the 1-based positions (Int) in `names` of the samples with that combination.

Two keys are the same combination when they are equal as JSON with sorted object keys (`conv:338`), so `1` and `1.0` differ. Without a primary factor, there is one group, with the key `[]`, holding every sample. A name that no sample has is an error.

```yaml
{fn: {name: factor-combinations, args: [{field: d.quantities.samples}, {field: samples}, {field: factors}]}}
# [{key: [{factor: disease, value: {cv: NCIT, ...}}], members: [1, 2, 3, 4]}, ...]
```

#### formula-check

`args: [sheet, name, parsed, stated]`. A stated sum formula checked against the name, as `Converter.formula` (`conv:291-309`) does. `parsed` is the name's [`goslin-parse`](#goslin-parse), which may be Null, and `stated` the stated formula. `sheet` and `name` are read as text for the notes. The result is an Obj `{formula, mass}`:

1. When nothing is stated (Null, `""`, or anything else Python takes as false), the result is `parsed`'s formula and mass, both Null when the name does not parse.
2. Otherwise the stated value is read as text, with its whitespace normalised as in [cell rendering](#cell-rendering). When it is not plain (all of it must match `(?:[A-Z][a-z]?\d*)+`), the result is as in 1, with the note `<sheet>: sum formula '<stated>' of '<name>' is not a plain formula (e.g. C42H82NO8P); not written`.
3. When Goslin gives a plain formula, and the element counts of the two formulas differ, the result is the stated formula without a mass (Null). The note is `<sheet>: sum formula <stated> of '<name>' does not match the name (<formula>, Goslin); the stated formula is written, without a mass`. The counts are compared, not the texts: an element without a count counts 1, and repeats add up, so `H82C42NO8P1` matches `C42H82NO8P`.
4. Otherwise the result is the stated formula with `parsed`'s mass. When the name parses but Goslin gives no plain formula, the note is `<sheet>: sum formula <stated> of '<name>' not checked (Goslin gives no plain formula)`.

`\d` is any Unicode decimal digit.

```yaml
{fn: {name: formula-check, args: [{literal: Q_D1}, {field: l}, {fn: {name: goslin-parse, args: [{field: l}]}}, {field: note.sum_formula}]}}
```

## Cell rendering

An engine writes a rule's value into a cell as follows (`conv:112-126` and `conv:265`):

- **Text:** whitespace runs become one space, and the ends are trimmed (Python `" ".join(s.split())`).
- **Int, Dbl:** as [`number`](#number).
- **Param:** `[cv, accession, name, value]`. The cv is written only with an accession. The name and the value are written as text, with `"` made `'`, and in `"…"` when they contain a comma. The value is empty when it is `""`. A parameter without an accession and with an empty name renders as nothing.
- **Ref:** `element[n]`, with the index the engine gives that element.
- **List:** its items rendered and joined with `|`, empty ones left out.
- **Null:** the line is not written. In a table cell, Null is an error: use [`number`](#number) to write `null`.
- **Obj:** an error.

## Errors

A mapping file is rejected, naming the JSON pointer of the offending node, when:

- it is not valid YAML, or a key repeats;
- it does not satisfy `mapping.schema.json`;
- a `pos`, `{x#}` or column `{x#}` names a variable that no enclosing form binds;
- a `ref` names no `let` entry;
- a `let` name is `params`, a top-level instance key or a vocabulary group id;
- a `match` regex does not compile or lacks its group;
- a `format` template uses an argument it is not given, or holds a lone `{` or `}`;
- a literal holds a boolean;
- its `id` or `kind` differs from its file name or folder.

Applying a form to the wrong kind of value is an error at run time. Examples are a list where a text is needed, or a number for `aggregate` that is a text.
