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
package org.lifstools.mztab.submission.mapping;

import java.util.ArrayList;
import java.util.List;

/** The notes a mapping run collects for the person who submitted the data, in the order they were made. */
public final class Notes {

    private final List<String> notes = new ArrayList<>();

    /** Adds a note. */
    public void add(String note) {
        notes.add(note);
    }

    /** All notes, in order. */
    public List<String> all() {
        return List.copyOf(notes);
    }
}
