# The v3 example: a conformance example of the 2.0 output mapping

The example workbook of the LipidCompass submission template v3,
`templates/v3/LipidCompass_Submission_Template_v3_EXAMPLE.xlsx` of
[lipidcompass-submissions](https://github.com/lifs-tools/lipidcompass-submissions) at commit `bb8dcbf`, read into the
submission vocabulary and written as mzTab-M 2.0.0-M.

| File | What it is |
|---|---|
| `instance.json` | The vocabulary instance: what `tools/read_submission_template.py` reads from the example workbook (the `v3-workbook` input mapping). |
| `parameters.json` | The parameters of the output mapping `mztab-2.0.0-M`, one object per dataset, in the order of the files in `expected/`. |
| `expected/LCS-99999-01.mzTab`, `expected/LCS-99999-02.mzTab` | What `tools/convert_submission_template.py` writes for the two datasets with `--study-id LCS-99999`. |
| `allowed-differences.yaml` | The metadata lines, by key prefix, in which the output of `mztab-2.0.0-M` may differ from `expected/`: the webapp records itself as `software[3]`. |

An implementation of the output mapping is conformant when its output for `instance.json` with each object of
`parameters.json` equals the matching file in `expected/` after dropping the metadata lines that
`allowed-differences.yaml` lists and sorting the metadata lines by key. The table lines keep their order.

## Refreshing the example

Run these commands in a lipidcompass-submissions checkout at the pinned commit, and copy `instance.json` and
`expected/` here:

```bash
uv run --frozen python tools/read_submission_template.py templates/v3/LipidCompass_Submission_Template_v3_EXAMPLE.xlsx -o instance.json
uv run --frozen python tools/convert_submission_template.py templates/v3/LipidCompass_Submission_Template_v3_EXAMPLE.xlsx -o expected --study-id LCS-99999
```

The expected files need Python 3.12 or later. pygoslin adds up a lipid's mass with Python's builtin `sum`, which
is compensated from Python 3.12 on, so an older Python can give masses that differ in the last digit.
