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
package org.lifstools.mztab.validator.webapp.sections;

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvParser;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.lifstools.mztab.validator.webapp.service.StorageService;
import org.lifstools.mztab.validator.webapp.service.StorageService.SLOT;
import org.lifstools.mztab.validator.webapp.session.SectionsSummary;
import org.lifstools.mztab.validator.webapp.session.TableSize;
import org.lifstools.mztab2.io.MzTabWriterDefaults;
import org.lifstools.mztab2.model.MzTab;
import org.springframework.stereotype.Component;
import uk.ac.ebi.pride.jmztab2.model.MZTabConstants;
import uk.ac.ebi.pride.jmztab2.utils.errors.MZTabException;

/**
 * Writes the sections of a parsed file to the session's storage: the
 * output of jmzTab-m's writer (mzTab column names and cell strings) as JSON
 * Lines.
 */
@Component
public class SectionWriter {

    static final String METADATA_FILE = "metadata.jsonl";

    /** jmzTab-m writes tab separated cells without quoting or escaping. */
    private static final CsvSchema TAB_SEPARATED = CsvSchema.emptySchema().withColumnSeparator('\t')
        .withoutQuoteChar().withoutEscapeChar();

    private final StorageService storage;
    private final ObjectMapper json = new ObjectMapper();

    public SectionWriter(StorageService storage) {
        this.storage = storage;
    }

    /**
     * The metadata lines and table rows as written by jmzTab-m.
     *
     * @param metadata the MTD lines, each PREFIX, KEY, VALUE
     * @param tables the header and rows of each present table
     */
    public record Rendered(List<List<String>> metadata, Map<Section, List<List<String>>> tables) {

    }

    /**
     * @return the sizes of the written sections; a table that is empty or
     * absent writes no file and has a {@code null} size
     */
    public SectionsSummary write(UUID id, MzTab mzTab) {
        try {
            Rendered rendered = render(mzTab);
            ByteArrayOutputStream metadata = new ByteArrayOutputStream();
            int line = 1;
            for (List<String> cells : rendered.metadata()) {
                writeLine(metadata, new MetadataEntry(line++, cells.get(1), cells.get(2)));
            }
            storage.write(id, SLOT.SECTIONS, METADATA_FILE, new ByteArrayInputStream(metadata.toByteArray()));
            Map<Section, TableSize> sizes = new EnumMap<>(Section.class);
            for (Map.Entry<Section, List<List<String>>> table : rendered.tables().entrySet()) {
                List<List<String>> rows = table.getValue();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                for (List<String> row : rows) {
                    // the first column is the line prefix (SMH, SML, ...), not part of the table
                    writeLine(out, row.subList(1, row.size()));
                }
                storage.write(id, SLOT.SECTIONS, table.getKey().fileName(), new ByteArrayInputStream(out.toByteArray()));
                sizes.put(table.getKey(), new TableSize(rows.size() - 1, rows.get(0).size() - 1));
            }
            return new SectionsSummary(rendered.metadata().size(), sizes.get(Section.SML), sizes.get(Section.SMF),
                sizes.get(Section.SME));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write the sections of validation " + id, e);
        }
    }

    private void writeLine(ByteArrayOutputStream out, Object value) throws IOException {
        out.write(json.writeValueAsBytes(value));
        out.write('\n');
    }

    /**
     * Renders metadata and tables with jmzTab-m's writer defaults.
     */
    public static Rendered render(MzTab mzTab) throws IOException {
        MzTabWriterDefaults defaults = new MzTabWriterDefaults();
        List<List<String>> metadata = new ArrayList<>();
        if (mzTab.getMetadata() != null) {
            if (mzTab.getMetadata().getMzTabVersion() == null) {
                // default version if not set
                mzTab.getMetadata().mzTabVersion(MZTabConstants.VERSION_MZTAB_M);
            }
            CsvMapper mapper = defaults.metadataMapper();
            StringWriter writer = new StringWriter();
            mapper.writer(defaults.metaDataSchema(mapper)).writeValue(writer, mzTab.getMetadata());
            for (List<String> cells : cells(writer.toString())) {
                metadata.add(pad(cells, 3));
            }
        }
        Map<Section, List<List<String>>> tables = new EnumMap<>(Section.class);
        try {
            if (notEmpty(mzTab.getSmallMoleculeSummary())) {
                CsvMapper mapper = defaults.smallMoleculeSummaryMapper();
                tables.put(Section.SML, table(mapper, defaults.smallMoleculeSummarySchema(mapper, mzTab),
                    mzTab.getSmallMoleculeSummary()));
            }
            if (notEmpty(mzTab.getSmallMoleculeFeature())) {
                CsvMapper mapper = defaults.smallMoleculeFeatureMapper();
                tables.put(Section.SMF, table(mapper, defaults.smallMoleculeFeatureSchema(mapper, mzTab),
                    mzTab.getSmallMoleculeFeature()));
            }
            if (notEmpty(mzTab.getSmallMoleculeEvidence())) {
                CsvMapper mapper = defaults.smallMoleculeEvidenceMapper();
                tables.put(Section.SME, table(mapper, defaults.smallMoleculeEvidenceSchema(mapper, mzTab),
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
