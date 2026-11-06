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
package org.lifstools.mztab.validator.webapp.workingcopy;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Generates the file shape of the largest real file the editor must handle
 * (design section 9): 846 SML rows of 1,990 columns, 1,521 assays and 5,540
 * metadata lines, about 32 MB. The content is deterministic. It is meant for
 * measuring, not for being a valid study.
 */
final class LargeFiles {

    static final int ROWS = 846;
    static final int COLUMNS = 1990;
    static final int ASSAYS = 1521;
    static final int METADATA_LINES = 5540;
    /** Cells of the SML header that are not the prefix, abundance or optional columns. */
    private static final String[] FIXED = {"SML_ID", "SMF_ID_REFS", "database_identifier", "chemical_formula",
        "smiles", "inchi", "chemical_name", "uri", "theoretical_neutral_mass", "adduct_ions", "reliability",
        "best_id_confidence_measure", "best_id_confidence_value"};

    private LargeFiles() {
    }

    /** Writes the file to {@code target}; returns the number of metadata lines written. */
    static void write(Path target) throws IOException {
        try (OutputStream out = Files.newOutputStream(target);
            Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
            int mtd = 0;
            String[] head = {"mzTab-version\t2.0.0-M", "mzTab-ID\tLARGE-1", "description\tGenerated for measurements",
                "cv[1]-label\tMS", "cv[1]-full_name\tPSI-MS controlled vocabulary", "cv[1]-version\t4.0.18",
                "cv[1]-uri\thttps://github.com/HUPO-PSI/psi-ms-CV/blob/master/psi-ms.obo",
                "cv[2]-label\tUO", "cv[2]-full_name\tUnits of Measurement Ontology", "cv[2]-version\t2017-09-25",
                "cv[2]-uri\thttp://purl.obolibrary.org/obo/uo.owl",
                "quantification_method\t[MS, MS:1001838, SRM quantitation analysis, ]",
                "sample_processing[1]\t[MS, MS:1000831, sample preparation, ]",
                "instrument[1]-name\t[MS, MS:1001911, Q Exactive , ]",
                "software[1]\t[MS, MS:1000532, Xcalibur,2.8-280502/2.8.1.2806]",
                "ms_run[1]-location\tfile:///large.mzML",
                "ms_run[1]-format\t[MS, MS:1000584, mzML file, ]",
                "ms_run[1]-id_format\t[MS, MS:1000768, Thermo nativeID format, ]",
                "ms_run[1]-scan_polarity[1]\t[MS, MS:1000130, positive scan, ]",
                "study_variable[1]\tAll", "study_variable[1]-assay_refs\tassay[1]",
                "study_variable[1]-description\tAll assays",
                "small_molecule-quantification_unit\t[UO, UO:0000072, picomolal, ]",
                "small_molecule_feature-quantification_unit\t[UO, UO:0000072, picomolal, ]",
                "small_molecule-identification_reliability\t[MS, MS:1002896, compound identification confidence level, ]",
                "database[1]\t[,, LipidMaps, ]", "database[1]-prefix\tLM", "database[1]-version\t2017-12",
                "database[1]-uri\thttp://www.lipidmaps.org/",
                "id_confidence_measure[1]\t[MS, MS:1002890, fragmentation score, ]"};
            for (String h : head) {
                w.write("MTD\t" + h + "\n");
                mtd++;
            }
            int samples = METADATA_LINES - head.length - 3 * ASSAYS;
            for (int a = 1; a <= ASSAYS; a++) {
                w.write("MTD\tassay[" + a + "]\tAssay " + a + "\n");
                w.write("MTD\tassay[" + a + "]-ms_run_ref\tms_run[1]\n");
                w.write("MTD\tassay[" + a + "]-sample_ref\tsample[" + (1 + (a - 1) % samples) + "]\n");
                mtd += 3;
            }
            for (int s = 1; mtd < METADATA_LINES; s++, mtd++) {
                w.write("MTD\tsample[" + s + "]\tSample " + s + "\n");
            }
            w.write("\n");
            StringBuilder h = new StringBuilder("SMH");
            for (String f : FIXED) {
                h.append('\t').append(f);
            }
            for (int a = 1; a <= ASSAYS; a++) {
                h.append("\tabundance_assay[").append(a).append(']');
            }
            int optional = COLUMNS - 1 - FIXED.length - ASSAYS - 2;
            h.append("\tabundance_study_variable[1]\tabundance_variation_study_variable[1]");
            for (int o = 1; o <= optional; o++) {
                h.append("\topt_global_extra").append(o);
            }
            w.write(h.append('\n').toString());
            for (int r = 1; r <= ROWS; r++) {
                StringBuilder row = new StringBuilder("SML");
                row.append('\t').append(r).append("\tnull\tLM:LMSP").append(r).append("\tnull\tnull\tnull\tLipid ").append(r)
                    .append("\tnull\t").append(400 + r * 0.001234).append("\tnull\t3\tnull\tnull");
                for (int a = 1; a <= ASSAYS; a++) {
                    row.append('\t').append(1000000.123456789 + r * 31L + a * 17L * 0.987654321);
                }
                row.append("\t1234.5678\t0.1234");
                for (int o = 1; o <= optional; o++) {
                    row.append('\t').append(1000000.123456789 + r * 7L + o * 13L * 0.987654321);
                }
                w.write(row.append('\n').toString());
            }
        }
    }
}
