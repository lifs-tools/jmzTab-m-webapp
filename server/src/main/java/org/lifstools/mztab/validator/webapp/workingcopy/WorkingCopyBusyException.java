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
 * The copy's database stayed locked by another change past the busy timeout (SQLite BUSY or LOCKED). The request
 * may be retried; nothing was changed by it.
 */
public class WorkingCopyBusyException extends RuntimeException {

    public WorkingCopyBusyException(Throwable cause) {
        super("The file is busy with another change. Please try again in a moment.", cause);
    }

    /** Whether {@code e} or one of its causes is SQLite reporting a busy or locked database. */
    static boolean isBusy(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof org.sqlite.SQLiteException s) {
                String code = s.getResultCode() == null ? "" : s.getResultCode().name();
                return code.startsWith("SQLITE_BUSY") || code.startsWith("SQLITE_LOCKED");
            }
        }
        return false;
    }
}
