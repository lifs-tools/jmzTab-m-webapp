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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;
import lombok.Data;

/**
 * A validation finding. The JSON is identical to jmzTab-m's
 * {@code ValidationMessage}, so the engines and the REST API can exchange it
 * without a model dependency.
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"code", "category", "message_type", "message", "line_number", "suggestion", "rule_id", "location"})
public class ValidationMessage {

    /** The kind of check that produced a message. */
    public enum Category {
        FORMAT, LOGICAL, CROSS_CHECK;

        @JsonValue
        public String value() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The severity of a message. */
    public enum MessageType {
        ERROR, WARN, INFO;

        @JsonValue
        public String value() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    @JsonProperty("code")
    private String code;

    @JsonProperty("category")
    private Category category;

    @JsonProperty("message_type")
    private MessageType messageType;

    @JsonProperty("message")
    private String message;

    @JsonProperty("line_number")
    private Long lineNumber;

    @JsonProperty("suggestion")
    private String suggestion;

    @JsonProperty("rule_id")
    private String ruleId;

    @JsonProperty("location")
    private ValidationLocation location;

    /**
     * @param text the message text
     * @return a format error without code and line
     */
    public static ValidationMessage error(String text) {
        return of(MessageType.ERROR, text);
    }

    /**
     * @param text the message text
     * @return a format information without code and line
     */
    public static ValidationMessage info(String text) {
        return of(MessageType.INFO, text);
    }

    private static ValidationMessage of(MessageType type, String text) {
        ValidationMessage m = new ValidationMessage();
        m.setCode("");
        m.setCategory(Category.FORMAT);
        m.setMessageType(type);
        m.setMessage(text);
        m.setLineNumber(-1L);
        return m;
    }
}
