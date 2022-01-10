/*
 * Copyright 2017 Leibniz Institut für Analytische Wissenschaften - ISAS e.V..
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
package org.lifstools.mztab.validator.webapp.domain;

import de.isas.mztab2.model.ValidationMessage;
import java.util.ArrayList;
import java.util.List;
import org.lifstools.mztab.validator.webapp.service.ValidationService.Status;
import java.util.EnumMap;
import java.util.Map;
import lombok.Data;

/**
 * A tool result represents the result of a tool processing incovation.
 *
 * @author Nils Hoffmann <nils.hoffmann@isas.de>
 */
@Data
public class ToolResult {

    public static enum Keys {
        MZTABVERSION, MAXERRORS, VALIDATIONLEVEL, CHECKCVMAPPING
    };

    private List<ValidationMessage> messages = new ArrayList<>();

    private Exception exception;

    private Status status;

    private Map<ToolResult.Keys, String> parameters = new EnumMap<>(Keys.class);

    public ToolResult() {

    }

    public ToolResult(Exception exception, Status status) {
        this();
        this.exception = exception;
        this.status = status;
    }

}
