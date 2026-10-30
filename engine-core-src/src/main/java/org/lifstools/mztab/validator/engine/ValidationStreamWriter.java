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

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.lifstools.mztab.validator.engine.api.SectionSizes;
import org.lifstools.mztab.validator.engine.api.TableDims;
import org.lifstools.mztab.validator.engine.api.ValidationEvent;
import org.lifstools.mztab.validator.engine.api.ValidationMessage;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Writes a validation result as NDJSON, one {@link ValidationEvent} per line:
 * the outcome, then the metadata lines and each present table with its rows,
 * then the end event. A stream without the end event is incomplete.
 */
@Component
public class ValidationStreamWriter {

    private final ObjectWriter writer;

    public ValidationStreamWriter(ObjectMapper objectMapper) {
        this.writer = objectMapper.writerFor(ValidationEvent.class)
            .without(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
    }

    /**
     * @param out the response body; it is flushed after the metadata and after each table, and not closed
     * @param mzTabVersion the declared version, if any
     * @param outcome the validation result
     * @param rendered the sections of the parsed file; {@code null} if it was
     * not parsed or could not be rendered
     */
    public void write(OutputStream out, @Nullable String mzTabVersion, EngineValidator.Outcome outcome,
        @Nullable SectionRenderer.Rendered rendered) throws IOException {
        List<ValidationMessage> messages = outcome.messages();
        line(out, new ValidationEvent.Outcome(outcome.mzTab() != null, mzTabVersion, messages));
        SectionSizes sizes = null;
        if (rendered != null) {
            int number = 1;
            for (List<String> cells : rendered.metadata()) {
                line(out, new ValidationEvent.MetadataLine(number++, cells.get(1), cells.get(2)));
            }
            out.flush();
            Map<SectionRenderer.Table, TableDims> dims = new EnumMap<>(SectionRenderer.Table.class);
            for (Map.Entry<SectionRenderer.Table, List<List<String>>> table : rendered.tables().entrySet()) {
                String section = table.getKey().name();
                List<List<String>> rows = table.getValue();
                // the first column is the line prefix (SMH, SML, ...), not part of the table
                line(out, new ValidationEvent.TableHeader(section, cells(rows.get(0))));
                for (List<String> row : rows.subList(1, rows.size())) {
                    line(out, new ValidationEvent.TableRowCells(section, cells(row)));
                }
                out.flush();
                dims.put(table.getKey(), new TableDims(rows.size() - 1, rows.get(0).size() - 1));
            }
            sizes = new SectionSizes(rendered.metadata().size(), dims.get(SectionRenderer.Table.SML),
                dims.get(SectionRenderer.Table.SMF), dims.get(SectionRenderer.Table.SME));
        }
        line(out, new ValidationEvent.End(sizes));
        out.flush();
    }

    private static List<String> cells(List<String> line) {
        return line.subList(1, line.size());
    }

    private void line(OutputStream out, ValidationEvent event) throws IOException {
        writer.writeValue(out, event);
        out.write('\n');
    }
}
