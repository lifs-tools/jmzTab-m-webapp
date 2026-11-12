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
package org.lifstools.mztab.validator.engine;

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvParser;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.lifstools.mztab2.io.MzTabWriterDefaults;
import org.lifstools.mztab2.model.MzTab;
import uk.ac.ebi.pride.jmztab2.utils.errors.MZTabException;

/**
 * Renders the sections of a parsed file as jmzTab-m's writer writes them:
 * mzTab column names and cell strings. Ported from the webapp's
 * {@code SectionWriter.render}; unlike it, a file without
 * {@code mzTab-version} is rendered without one, although jmzTab-m's
 * writer fills in its default version.
 */
public final class SectionRenderer {

    /** jmzTab-m writes tab separated cells without quoting or escaping. */
    private static final CsvSchema TAB_SEPARATED = CsvSchema.emptySchema().withColumnSeparator('\t')
        .withoutQuoteChar().withoutEscapeChar();

    private static final String MZTAB_VERSION = "mzTab-version";

    /** The table sections, in file order. */
    public enum Table {
        SML, SMF, SME
    }

    private SectionRenderer() {
    }

    /**
     * The metadata lines and table rows as written by jmzTab-m.
     *
     * @param metadata the MTD lines, each PREFIX, KEY, VALUE
     * @param tables the header and rows of each present table, each starting
     * with its line prefix (SMH, SML, ...)
     */
    public record Rendered(List<List<String>> metadata, Map<Table, List<List<String>>> tables) {

    }

    /**
     * Renders metadata and tables with jmzTab-m's writer defaults.
     */
    public static Rendered render(MzTab mzTab) throws IOException {
        MzTabWriterDefaults defaults = new MzTabWriterDefaults();
        List<List<String>> metadata = new ArrayList<>();
        if (mzTab.getMetadata() != null) {
            boolean versionDeclared = mzTab.getMetadata().getMzTabVersion() != null;
            CsvMapper mapper = defaults.metadataMapper();
            StringWriter writer = new StringWriter();
            try {
                mapper.writer(defaults.metaDataSchema(mapper)).writeValue(writer, mzTab.getMetadata());
            } finally {
                if (!versionDeclared) {
                    // jmzTab-m's metadata serializer sets its default version on the model
                    mzTab.getMetadata().setMzTabVersion(null);
                }
            }
            for (List<String> cells : cells(writer.toString())) {
                // and writes it; the sections show the file as it is
                if (!versionDeclared && cells.size() > 1 && MZTAB_VERSION.equals(cells.get(1))) {
                    continue;
                }
                metadata.add(pad(cells, 3));
            }
        }
        Map<Table, List<List<String>>> tables = new EnumMap<>(Table.class);
        try {
            if (notEmpty(mzTab.getSmallMoleculeSummary())) {
                CsvMapper mapper = defaults.smallMoleculeSummaryMapper();
                tables.put(Table.SML, table(mapper, defaults.smallMoleculeSummarySchema(mapper, mzTab),
                    mzTab.getSmallMoleculeSummary()));
            }
            if (notEmpty(mzTab.getSmallMoleculeFeature())) {
                CsvMapper mapper = defaults.smallMoleculeFeatureMapper();
                tables.put(Table.SMF, table(mapper, defaults.smallMoleculeFeatureSchema(mapper, mzTab),
                    mzTab.getSmallMoleculeFeature()));
            }
            if (notEmpty(mzTab.getSmallMoleculeEvidence())) {
                CsvMapper mapper = defaults.smallMoleculeEvidenceMapper();
                tables.put(Table.SME, table(mapper, defaults.smallMoleculeEvidenceSchema(mapper, mzTab),
                    mzTab.getSmallMoleculeEvidence()));
            }
        } catch (MZTabException e) {
            throw new IOException(e);
        }
        return new Rendered(metadata, tables);
    }

    private static boolean notEmpty(List<?> list) {
        return list != null && !list.isEmpty();
    }

    private static List<List<String>> table(CsvMapper mapper, CsvSchema schema, List<?> rows) throws IOException {
        StringWriter writer = new StringWriter();
        mapper.writer(schema).writeValue(writer, rows);
        List<List<String>> lines = cells(writer.toString());
        int columns = lines.get(0).size();
        List<List<String>> padded = new ArrayList<>(lines.size());
        for (List<String> line : lines) {
            padded.add(pad(line, columns));
        }
        return padded;
    }

    /** Reads the tab separated text with Jackson's CSV reader; blank lines are skipped. */
    private static List<List<String>> cells(String text) throws IOException {
        List<List<String>> lines = new ArrayList<>();
        try (MappingIterator<String[]> it = new CsvMapper().enable(CsvParser.Feature.WRAP_AS_ARRAY)
            .readerFor(String[].class).with(TAB_SEPARATED).readValues(text)) {
            while (it.hasNext()) {
                String[] row = it.next();
                if (row.length == 0 || row.length == 1 && row[0].isEmpty()) {
                    continue;
                }
                lines.add(Arrays.asList(row));
            }
        }
        return lines;
    }

    private static List<String> pad(List<String> cells, int size) {
        if (cells.size() >= size) {
            return cells;
        }
        List<String> padded = new ArrayList<>(cells);
        while (padded.size() < size) {
            padded.add("");
        }
        return padded;
    }
}
