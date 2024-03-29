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
package org.lifstools.mztab.validator.webapp.service.validation;

import org.lifstools.mztab2.model.ValidationMessage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 *
 * @author Nils Hoffmann nils.hoffmann@cebitec.uni-bielefeld.de;
 */
public interface WebValidator {

    public List<ValidationMessage> validate(Path filepath,
        String validationLevel, int maxErrors, boolean checkCvMapping, Path validationFile) throws IllegalStateException, IOException;

    public Map<String, List<Map<String,String>>> parse(Path filepath, String validationLevel,
        int maxErrors) throws IOException;
}
