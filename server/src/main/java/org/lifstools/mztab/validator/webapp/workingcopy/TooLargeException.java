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

/**
 * The file exceeds a cap of the editor ({@link CopyText#MAX_LINES} lines, {@link CopyText#MAX_CELLS} cells in one
 * line). The message names the cap in plain words.
 */
public class TooLargeException extends RuntimeException {

    public TooLargeException(String message) {
        super(message);
    }

    /** More than {@link CopyText#MAX_LINES} lines. */
    static TooLargeException lines() {
        return new TooLargeException("The file has more than 2,000,000 lines. The editor opens files of up to "
            + "2,000,000 lines.");
    }

    /** More than {@link CopyText#MAX_CELLS} cells in line {@code number}. */
    static TooLargeException cells(long number) {
        return new TooLargeException("Line " + number + " has more than 20,000 cells. The editor opens files with "
            + "up to 20,000 cells per line.");
    }
}
