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

/** The file is not valid UTF-8. */
public class NotUtf8Exception extends RuntimeException {

    private final long lineNumber;

    public NotUtf8Exception(long lineNumber) {
        super("The file is not encoded as UTF-8; the first line that is not is line " + lineNumber + ".");
        this.lineNumber = lineNumber;
    }

    public long lineNumber() {
        return lineNumber;
    }
}
