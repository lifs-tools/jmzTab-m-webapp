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

import java.util.List;

/**
 * The outcome of applying an output mapping to a working copy.
 *
 * <p><b>Limit.</b> {@code edits} holds one {@link LineEdit.InsertLine} per new line, and the server refuses a batch of
 * more than {@code WorkingCopyEditor.MAX_OPERATIONS} (5,000) operations. A large study, about 300 samples and 3,000
 * lipids, exceeds it. Before the webapp applies these edits (N-2), an {@code InsertLines(before, List<String>)}
 * operation shared by engine-api and the server, or a documented cap on the size of a mapping batch, is decided
 * (design §5.7). Splitting the edits into several batches is not an option: it breaks single-step undo.
 *
 * @param edits the edits, in the order they are to be applied as one batch
 * @param suggestions every line the mapping writes, with its state, in the order written
 * @param notes notes for the person who submitted the data
 * @param text the working copy's text with the edits applied, as a preview: it may differ from the text the
 *     server has after applying {@code edits} only in the last line's terminator (inserted lines here always end
 *     with a line break, so a new file ends with one)
 */
public record MappingApplyResult(List<LineEdit> edits, List<Suggestion> suggestions, List<String> notes, String text) {

    public MappingApplyResult {
        edits = List.copyOf(edits);
        suggestions = List.copyOf(suggestions);
        notes = List.copyOf(notes);
    }
}
