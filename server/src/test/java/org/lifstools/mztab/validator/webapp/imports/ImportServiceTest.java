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
package org.lifstools.mztab.validator.webapp.imports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lifstools.mztab.validator.engine.api.EngineInfo;
import org.lifstools.mztab.validator.engine.api.LineEdit;
import org.lifstools.mztab.validator.engine.api.MappingApplyRequest;
import org.lifstools.mztab.validator.engine.api.MappingApplyResult;
import org.lifstools.mztab.validator.engine.api.Suggestion;
import org.lifstools.mztab.validator.engine.api.SuggestionState;
import org.lifstools.mztab.validator.webapp.engine.EngineClient;
import org.lifstools.mztab.validator.webapp.engine.EngineRegistry;
import org.lifstools.mztab.validator.webapp.workingcopy.EditOperation;
import org.lifstools.mztab.validator.webapp.workingcopy.LineEdits;
import org.lifstools.mztab.validator.webapp.workingcopy.StaleRevisionException;
import org.lifstools.mztab.validator.webapp.workingcopy.TooLargeException;
import org.lifstools.mztab.validator.webapp.workingcopy.WorkingCopyDb;
import org.lifstools.mztab.validator.webapp.workingcopy.WorkingCopyInfo;
import org.lifstools.mztab.validator.webapp.workingcopy.WorkingCopyService;
import org.mockito.ArgumentCaptor;

/** The import preview, with the copy, the registry and the engine stubbed. */
class ImportServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID ID = UUID.randomUUID();
    private static final String TEXT = "MTD\tmzTab-version\t2.0.0-M\nMTD\tmzTab-ID\tx\n";

    private final WorkingCopyService copies = mock(WorkingCopyService.class);
    private final EngineRegistry registry = mock(EngineRegistry.class);
    private final EngineClient engine = mock(EngineClient.class);
    private final WorkingCopyDb db = mock(WorkingCopyDb.class);
    private final ImportService service = new ImportService(copies, registry, new OutputMappings());
    private long maxUploadBytes = 1_000_000;
    private long dbRevision = 4;
    private String text = TEXT;

    private static final MappingApplyResult RESULT = new MappingApplyResult(
        List.of(new LineEdit.InsertLine(3, "MTD\tdescription\td"), new LineEdit.SetCell(2, 2, "y"),
            new LineEdit.InsertLines(1, List.of("a", "b"))),
        List.of(new Suggestion("k", "v", SuggestionState.values()[0])), List.of("a note"), "preview text");

    @BeforeEach
    void stubs() throws Exception {
        copyOf("2.0.0-M");
        when(registry.forLine("2.0")).thenReturn(Optional.of(engine));
        when(registry.lastKnownInfo()).thenAnswer(i -> Map.of("2.0", Optional.of(
            new EngineInfo("2.0", List.of("2.0.0-M"), "1", "b", maxUploadBytes, 1))));
        when(engine.applyMapping(any())).thenReturn(RESULT);
        when(copies.read(eq(ID), any())).thenAnswer(i -> ((Function<WorkingCopyDb, ?>) i.getArgument(1)).apply(db));
        doAnswer(i -> {
            ((OutputStream) i.getArgument(0)).write(text.getBytes(StandardCharsets.UTF_8));
            return dbRevision;
        }).when(db).writeRevisionTo(any(OutputStream.class));
    }

    private void copyOf(String version) {
        WorkingCopyInfo info = mock(WorkingCopyInfo.class);
        when(info.mzTabVersion()).thenReturn(version);
        when(copies.info(ID)).thenReturn(info);
    }

    private static JsonNode instance(int datasets) throws Exception {
        StringBuilder b = new StringBuilder("{\"datasets\":[");
        for (int i = 1; i <= datasets; i++) {
            b.append(i > 1 ? "," : "").append("{\"dataset_id\":\"D").append(i).append("\"}");
        }
        return JSON.readTree(b.append("]}").toString());
    }

    private static ImportRequest request(long revision, JsonNode instance, Integer index, boolean keep) {
        return new ImportRequest("vocabulary-instance", revision, instance, index, "LCS-1", keep);
    }

    @Test
    void previewCarriesBatchAtRevision() throws Exception {
        ImportPreview p = service.preview(ID, request(4, instance(1), null, true));

        assertEquals(4, p.revision());
        assertEquals(4, p.batch().revision());
        assertNull(p.batch().changeId());
        assertNull(p.batch().generation());
        List<EditOperation> expected = RESULT.edits().stream().map(LineEdits::toOperation).toList();
        assertEquals(expected, p.batch().operations());
        assertEquals("mztab-2.0.0-M", p.mapping());
        assertEquals(RESULT.suggestions(), p.suggestions());
        assertEquals(List.of("a note"), p.notes());
    }

    @Test
    void sendsCopyTextAndParameters() throws Exception {
        service.preview(ID, request(4, instance(2), 2, true));

        ArgumentCaptor<MappingApplyRequest> sent = ArgumentCaptor.forClass(MappingApplyRequest.class);
        verify(engine).applyMapping(sent.capture());
        MappingApplyRequest r = sent.getValue();
        assertEquals(TEXT, r.text());
        assertEquals("mztab-2.0.0-M", r.mapping());
        assertEquals(new OutputMappings().forVersion("2.0.0-M").orElseThrow().version(), r.mappingVersion());
        assertEquals(Map.of("study_id", "LCS-1", "dataset_id", "D2", "dataset_index", "2"), r.parameters());
        assertTrue(r.provenance());
        assertNull(r.inputMapping());
        assertTrue(r.keepExisting());
        service.preview(ID, request(4, instance(1), null, false));
        verify(engine, org.mockito.Mockito.times(2)).applyMapping(sent.capture());
        assertEquals(Map.of("study_id", "LCS-1", "dataset_id", "D1", "dataset_index", "1"),
            sent.getValue().parameters(), "the only dataset needs no index");
        assertEquals(false, sent.getValue().keepExisting());
    }

    @Test
    void staleRevisionIs409() throws Exception {
        StaleRevisionException e = assertThrows(StaleRevisionException.class,
            () -> service.preview(ID, request(3, instance(1), null, true)));

        assertEquals(4, e.expected());
        assertEquals(3, e.actual());
        verify(engine, never()).applyMapping(any());
    }

    @Test
    void datasetIndexOutOfRangeIs400() throws Exception {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> service.preview(ID, request(4, instance(3), 4, true)));
        assertEquals("dataset 4 of 3", e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> service.preview(ID, request(4, instance(3), 0, true)));
        assertEquals("dataset 0 of 3", e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> service.preview(ID, request(4, instance(2), null, true)));
        assertEquals("choose one of 2 datasets", e.getMessage());
        verify(engine, never()).applyMapping(any());
    }

    @Test
    void noMappingForVersion() throws Exception {
        copyOf("2.1.0-M");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> service.preview(ID, request(4, instance(1), null, true)));

        assertEquals("No output mapping for mzTab-M 2.1.0-M yet", e.getMessage());
        assertEquals(List.of(), service.kinds(ID));
        copyOf("2.0.0-M");
        assertEquals(List.of("vocabulary-instance"), service.kinds(ID));
    }

    @Test
    void unknownKindIs400() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> service.preview(ID,
            new ImportRequest("workbook", 4, instance(1), null, "LCS-1", true)));
    }

    @Test
    void textOverEngineLimitIs413() throws Exception {
        maxUploadBytes = TEXT.length() - 1;

        assertThrows(TooLargeException.class, () -> service.preview(ID, request(4, instance(1), null, true)));
        verify(engine, never()).applyMapping(any());

        maxUploadBytes = TEXT.length();
        service.preview(ID, request(4, instance(1), null, true));
    }
}
