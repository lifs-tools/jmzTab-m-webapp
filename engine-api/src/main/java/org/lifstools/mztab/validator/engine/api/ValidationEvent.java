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
package org.lifstools.mztab.validator.engine.api;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** One line of the engine's NDJSON validation stream. */
@Schema(description = "One line of the NDJSON validation stream; type names the event.",
    oneOf = {ValidationEvent.Outcome.class, ValidationEvent.MetadataLine.class, ValidationEvent.TableHeader.class,
        ValidationEvent.TableRowCells.class, ValidationEvent.End.class},
    discriminatorProperty = "type",
    discriminatorMapping = {
        @DiscriminatorMapping(value = "outcome", schema = ValidationEvent.Outcome.class),
        @DiscriminatorMapping(value = "metadata", schema = ValidationEvent.MetadataLine.class),
        @DiscriminatorMapping(value = "table", schema = ValidationEvent.TableHeader.class),
        @DiscriminatorMapping(value = "row", schema = ValidationEvent.TableRowCells.class),
        @DiscriminatorMapping(value = "end", schema = ValidationEvent.End.class)
    })
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = ValidationEvent.Outcome.class, name = "outcome"),
    @JsonSubTypes.Type(value = ValidationEvent.MetadataLine.class, name = "metadata"),
    @JsonSubTypes.Type(value = ValidationEvent.TableHeader.class, name = "table"),
    @JsonSubTypes.Type(value = ValidationEvent.TableRowCells.class, name = "row"),
    @JsonSubTypes.Type(value = ValidationEvent.End.class, name = "end")
})
public sealed interface ValidationEvent {

    /**
     * @param parsed whether the file could be parsed
     * @param mzTabVersion the declared version, if any
     * @param messages the findings
     */
    record Outcome(boolean parsed, String mzTabVersion, List<ValidationMessage> messages) implements ValidationEvent {
    }

    /**
     * @param line the line number
     * @param key the metadata key
     * @param value the metadata value
     */
    record MetadataLine(int line, String key, String value) implements ValidationEvent {
    }

    /**
     * @param section the section name
     * @param header the column names
     */
    record TableHeader(String section, List<String> header) implements ValidationEvent {
    }

    /**
     * @param section the section name
     * @param cells the cells of one row
     */
    record TableRowCells(String section, List<String> cells) implements ValidationEvent {
    }

    /**
     * Marks a complete stream.
     *
     * @param sections the section sizes
     */
    record End(SectionSizes sections) implements ValidationEvent {
    }
}
