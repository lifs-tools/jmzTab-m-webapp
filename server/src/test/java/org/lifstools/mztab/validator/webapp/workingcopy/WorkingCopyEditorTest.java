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

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lifstools.mztab.validator.webapp.workingcopy.EditOperation.*;

class WorkingCopyEditorTest {

    private static final String TEXT = "COM\tc\nMTD\tkey\tv\n"
        + "SMH\tSML_ID\tname\n"
        + "SML\t1\tPC 34:1\n"
        + "SML\t2\tPC 36:2\n"
        + "SML\t3\tPE 38:4\n"
        + "SFH\tSMF_ID\tx\n"
        + "SMF\t1\ta\n";

    @TempDir
    Path dir;
    WorkingCopyDb db;
    final WorkingCopyEditor editor = new WorkingCopyEditor();

    @BeforeEach
    void setUp() throws Exception {
        db = WorkingCopyDb.open(dir.resolve("e.db"));
        load(TEXT);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private void load(String text) throws Exception {
        db.importLines(CopyText.read(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),
            SourceEncoding.UTF_8));
    }

    private String text() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        db.writeTo(out);
        return out.toString(StandardCharsets.UTF_8);
    }

    private long apply(EditOperation... ops) {
        return editor.apply(db, new EditBatch(db.revision(), List.of(ops)));
    }

    @Test
    void setCellChangesOneCell() throws Exception {
        assertEquals(1, apply(new SetCell(4, 2, "PC 35:1")));
        assertEquals(TEXT.replace("PC 34:1", "PC 35:1"), text());
        assertEquals(2, apply(new SetCell(4, 3, "extra")));
        assertEquals("SML\t1\tPC 35:1\textra", db.line(4).orElseThrow().text());
    }

    @Test
    void metadataValueAndKey() throws Exception {
        apply(new SetMetadataValue(2, "w"), new RenameMetadataKey(2, "k2"));
        assertEquals("MTD\tk2\tw", db.line(2).orElseThrow().text());
        assertThrows(IllegalArgumentException.class, () -> apply(new SetMetadataValue(4, "x")));
    }

    @Test
    void insertColumnAlignsEveryRow() {
        apply(new InsertColumn("SMH", 2, "opt_global_x", "null"));
        assertEquals("SMH\tSML_ID\topt_global_x\tname", db.line(3).orElseThrow().text());
        for (int n = 4; n <= 6; n++) {
            assertEquals(4, db.line(n).orElseThrow().cells().size());
            assertEquals("null", db.line(n).orElseThrow().cells().get(2));
        }
        assertEquals("SMF\t1\ta", db.line(8).orElseThrow().text());
    }

    @Test
    void deleteAndRenameColumn() {
        apply(new RenameColumn("SMH", "name", "label"));
        assertEquals("SMH\tSML_ID\tlabel", db.line(3).orElseThrow().text());
        apply(new DeleteColumn("SMH", "SML_ID"));
        assertEquals("SMH\tlabel", db.line(3).orElseThrow().text());
        assertEquals("SML\tPC 34:1", db.line(4).orElseThrow().text());
        assertThrows(IllegalArgumentException.class, () -> apply(new DeleteColumn("SMH", "nope")));
    }

    /** A header that names a column twice (cells 2 and 4), as a loaded file may. */
    private static final String DUPLICATES = "SMH\tSML_ID\tname\tx\tname\r\n"
        + "SML\t1\tPC 34:1\ta\tPC 34:1\r\n"
        + "SML\t2\t\tb\tPC 36:2\r\n"
        + "SML\t3\tnull\tc\t\r\n"
        + "SML\t4\tPE 38:4\td\tPE 38:5\r\n"
        + "SML\t5\tnull\r\n"
        + "SML\t6\t\te\tnull";

    @Test
    void positionedDeleteAndRenameNameTheColumn() throws Exception {
        load(DUPLICATES);
        apply(new DeleteColumn("SMH", "name", 4));
        assertEquals("SMH\tSML_ID\tname\tx", db.line(1).orElseThrow().text());
        assertEquals("SML\t4\tPE 38:4\td", db.line(5).orElseThrow().text());
        // a position that does not hold the name is refused, and nothing changes
        long revision = db.revision();
        assertThrows(IllegalArgumentException.class, () -> apply(new DeleteColumn("SMH", "name", 3)));
        assertThrows(IllegalArgumentException.class, () -> apply(new RenameColumn("SMH", "name", "label", 1)));
        assertThrows(IllegalArgumentException.class, () -> apply(new DeleteColumn("SMH", "name", 0)));
        assertThrows(IllegalArgumentException.class, () -> apply(new DeleteColumn("SMH", "name", 9)));
        assertEquals(revision, db.revision());
        apply(new RenameColumn("SMH", "name", "label", 2));
        assertEquals("SMH\tSML_ID\tlabel\tx", db.line(1).orElseThrow().text());
    }

    @Test
    void withoutAPositionTheFirstColumnOfTheNameIsUsed() throws Exception {
        load(DUPLICATES);
        apply(new DeleteColumn("SMH", "name"));
        assertEquals("SMH\tSML_ID\tx\tname", db.line(1).orElseThrow().text());
        assertEquals("SML\t1\ta\tPC 34:1", db.line(2).orElseThrow().text());
    }

    @Test
    void noEditCreatesADuplicateColumn() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> apply(new InsertColumn("SMH", 1, "name", "f")));
        assertThrows(IllegalArgumentException.class, () -> apply(new RenameColumn("SMH", "SML_ID", "name")));
        // renaming a column to its own name changes nothing and is allowed
        apply(new RenameColumn("SMH", "name", "name"));
        assertEquals(TEXT, text());
        // in a file that already has duplicates, renaming one of them to the name it shares is refused too
        load(DUPLICATES);
        assertThrows(IllegalArgumentException.class, () -> apply(new RenameColumn("SMH", "x", "name")));
        assertThrows(IllegalArgumentException.class, () -> apply(new InsertColumn("SMH", 1, "name", "f")));
        apply(new RenameColumn("SMH", "name", "name", 4));
        assertEquals(DUPLICATES, text());
    }

    @Test
    void lineEditsCreateNoRepeatedColumnName() throws Exception {
        // SetCell on a header line
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> apply(new SetCell(3, 2, "SML_ID")));
        assertTrue(e.getMessage().contains("column SML_ID"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> apply(new SetCell(3, 3, "name")));
        apply(new SetCell(3, 3, ""), new SetCell(3, 4, ""));
        apply(new SetCell(4, 2, "1"));
        assertEquals("SML\t1\t1", db.line(4).orElseThrow().text());
        // SetRawLine, also turning a row into a header
        load(TEXT);
        assertThrows(IllegalArgumentException.class, () -> apply(new SetRawLine(3, "SMH\tSML_ID\tSML_ID")));
        assertThrows(IllegalArgumentException.class, () -> apply(new SetRawLine(4, "SFH\ta\ta")));
        apply(new SetRawLine(4, "SML\t1\t1"), new SetRawLine(3, "SMH\tSML_ID\tlabel"));
        // the repeated values of a row are no repeat the header line already had
        assertThrows(IllegalArgumentException.class, () -> apply(new SetRawLine(4, "SFH\t1\t1")));
        // InsertLine of a header line
        assertThrows(IllegalArgumentException.class, () -> apply(new InsertLine(9, "SEH\tSME_ID\tSME_ID")));
        apply(new InsertLine(9, "SEH\tSME_ID\t\t"));
        // JoinWithPrevious whose result is a header line
        load(TEXT);
        apply(new InsertLine(4, "\tname"));
        assertThrows(IllegalArgumentException.class, () -> apply(new JoinWithPrevious(4)));
        apply(new SetRawLine(4, "\tlabel"), new JoinWithPrevious(4));
        assertEquals("SMH\tSML_ID\tname\tlabel", db.line(3).orElseThrow().text());
        // SplitCells whose result is a header line
        load("SMH\tname name\nSML\tx\n");
        e = assertThrows(IllegalArgumentException.class, () -> apply(new SplitCells(1)));
        assertTrue(e.getMessage().contains("column name"), e.getMessage());
        load("SMH\tSML_ID name\nSML\tx\n");
        apply(new SplitCells(1));
        assertEquals("SMH\tSML_ID\tname", db.line(1).orElseThrow().text());
        // a row line may repeat values
        load("SMH\tSML_ID\nSML\tPC 34:1 PC 34:1\n");
        apply(new SplitCells(2));
        assertEquals("SML\tPC\t34:1 PC 34:1", db.line(2).orElseThrow().text());
    }

    @Test
    void repeatsAFileAlreadyHasStayEditable() throws Exception {
        load(DUPLICATES);
        // editing the header leaves the existing repeat as it was
        apply(new SetCell(1, 3, "y"));
        apply(new SetCell(1, 4, "name"));
        apply(new SetRawLine(1, "SMH\tSML_ID\tname\tz\tname"));
        assertEquals("SMH\tSML_ID\tname\tz\tname", db.line(1).orElseThrow().text());
        // a third copy is refused
        assertThrows(IllegalArgumentException.class, () -> apply(new SetCell(1, 3, "name")));
        assertThrows(IllegalArgumentException.class, () -> apply(new SetCell(1, 5, "SML_ID")));
        // and the repeat can be resolved
        apply(new SetCell(1, 4, "label"));
        assertEquals("SMH\tSML_ID\tname\tz\tlabel", db.line(1).orElseThrow().text());
    }

    @Test
    void mergeColumnsKeepsAValuePerRowAndRemovesTheOthers() throws Exception {
        load(DUPLICATES);
        ByteArrayOutputStream before = new ByteArrayOutputStream();
        db.writeTo(before);

        apply(new MergeColumns("SMH", "name", 2, List.of(4)));

        assertEquals("SMH\tSML_ID\tname\tx\r\n"
            // both hold the same value
            + "SML\t1\tPC 34:1\ta\r\n"
            // the kept cell is empty: the other's value
            + "SML\t2\tPC 36:2\tb\r\n"
            // the kept cell is null and the other empty: null stays
            + "SML\t3\tnull\tc\r\n"
            // they disagree: the kept column wins
            + "SML\t4\tPE 38:4\td\r\n"
            // a short row
            + "SML\t5\tnull\r\n"
            + "SML\t6\t\te", text());

        editor.undo(db, 1);
        ByteArrayOutputStream undone = new ByteArrayOutputStream();
        db.writeTo(undone);
        assertArrayEquals(before.toByteArray(), undone.toByteArray());
    }

    @Test
    void mergeIntoTheLaterColumn() throws Exception {
        load(DUPLICATES);
        apply(new MergeColumns("SMH", "name", 4, List.of(2)));
        assertEquals("SMH\tSML_ID\tx\tname", db.line(1).orElseThrow().text());
        assertEquals("SML\t1\ta\tPC 34:1", db.line(2).orElseThrow().text());
        // neither holds a value: the kept cell stays as it is
        assertEquals("SML\t3\tc\t", db.line(4).orElseThrow().text());
        // the kept column wins where they disagree
        assertEquals("SML\t4\td\tPE 38:5", db.line(5).orElseThrow().text());
        assertEquals("SML\t5", db.line(6).orElseThrow().text());
        assertEquals("SML\t6\te\tnull", db.line(7).orElseThrow().text());
    }

    @Test
    void mergeFillsAKeptCellPastTheEndOfAShortRow() throws Exception {
        load("SMH\tA\tn\tB\tn\nSML\t1\tv\n");
        apply(new MergeColumns("SMH", "n", 4, List.of(2)));
        assertEquals("SMH\tA\tB\tn\nSML\t1\t\tv\n", text());
    }

    @Test
    void mergeTakesTheFirstValueAmongTheOthersInTheirOrder() throws Exception {
        load("SMH\tA\tn\tn\tn\nSML\t1\tnull\tfirst\tsecond\nSML\t2\t\t\tonly\nSML\t3\tnull\n"
            + "SML\t4\tnull\tshort\n");
        apply(new MergeColumns("SMH", "n", 2, List.of(4, 3)));
        assertEquals("SMH\tA\tn\nSML\t1\tsecond\nSML\t2\tonly\nSML\t3\tnull\nSML\t4\tshort\n", text());
    }

    @Test
    void mergeRefusesInvalidPositions() throws Exception {
        load(DUPLICATES);
        for (MergeColumns bad : List.of(
            new MergeColumns("SMH", "name", 2, List.of()),
            new MergeColumns("SMH", "name", 2, List.of(2)),
            new MergeColumns("SMH", "name", 2, List.of(4, 4)),
            new MergeColumns("SMH", "name", 3, List.of(4)),
            new MergeColumns("SMH", "name", 2, List.of(3)),
            new MergeColumns("SMH", "name", 2, List.of(9)),
            new MergeColumns("SML", "name", 2, List.of(4)),
            new MergeColumns("SMH", "nope", 2, List.of(4)))) {
            assertThrows(IllegalArgumentException.class, () -> apply(bad), bad.toString());
        }
        assertThrows(IllegalArgumentException.class,
            () -> apply(new MergeColumns("SMH", "name", 2, java.util.Arrays.asList(4, null))));
        assertEquals(0, db.revision());
        assertEquals(DUPLICATES, text());
    }

    @Test
    void anOversizedMergeIsRefusedBeforeItsPositionsAreChecked() throws Exception {
        load(DUPLICATES);
        List<Integer> huge = new ArrayList<>();
        for (int i = 0; i < 100_000; i++) {
            huge.add(4);
        }
        long start = System.nanoTime();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> apply(new MergeColumns("SMH", "name", 2, huge)));
        assertTrue(System.nanoTime() - start < 1_000_000_000L, "refused quickly");
        assertTrue(e.getMessage().contains("at most"), e.getMessage());
        assertEquals(DUPLICATES, text());
    }

    @Test
    void columnOperationsDeserialiseWithAndWithoutPosition() throws Exception {
        load(DUPLICATES);
        String json = """
            {"revision":0,"operations":[{"op":"DeleteColumn","header":"SMH","name":"x"},
            {"op":"RenameColumn","header":"SMH","name":"name","newName":"n2","position":3},
            {"op":"MergeColumns","header":"SMH","name":"name","keep":2,"others":[]}]}""";
        EditBatch batch = new ObjectMapper().readValue(json, EditBatch.class);
        assertEquals(new DeleteColumn("SMH", "x", null), batch.operations().get(0));
        assertEquals(new RenameColumn("SMH", "name", "n2", 3), batch.operations().get(1));
        assertEquals(new MergeColumns("SMH", "name", 2, List.of()), batch.operations().get(2));
    }

    @Test
    void joinWithPreviousRepairsSplitRow() throws Exception {
        load("SML\t1\tPC 34:1 with\n a newline\t5\n");
        apply(new JoinWithPrevious(2));
        assertEquals(1, db.lineCount());
        assertEquals("SML\t1\tPC 34:1 with a newline\t5", db.line(1).orElseThrow().text());
        assertEquals("SML", db.line(1).orElseThrow().section());
    }

    @Test
    void splitCellsAtSpaces() throws Exception {
        load("MTD\tstudy_variable[1]-assay_refs assay[1], assay[2]\n");
        apply(new SplitCells(1));
        assertEquals(List.of("MTD", "study_variable[1]-assay_refs", "assay[1], assay[2]"),
            db.line(1).orElseThrow().cells());
        assertThrows(IllegalArgumentException.class, () -> apply(new SplitCells(1)));
    }

    @Test
    void insertDeleteMoveSetRaw() throws Exception {
        apply(new InsertLine(2, "MTD\tnew\tvalue"));
        assertEquals("MTD\tnew\tvalue", db.line(2).orElseThrow().text());
        apply(new SetRawLine(2, "MTD\tother\tthing"));
        apply(new MoveRow(2, 5));
        assertEquals("MTD\tother\tthing", db.line(4).orElseThrow().text());
        assertEquals("MTD\tkey\tv", db.line(2).orElseThrow().text());
        apply(new DeleteLine(4));
        assertEquals(TEXT, text());
    }

    @Test
    void appendingToUnterminatedTextKeepsTheEndingRule() throws Exception {
        load("MTD\ta\tb\nMTD\tc\td");
        apply(new InsertLine(3, "MTD\te\tf"));
        assertEquals("MTD\ta\tb\nMTD\tc\td\nMTD\te\tf", text());
        apply(new MoveRow(1, 4));
        assertEquals("MTD\tc\td\nMTD\te\tf\nMTD\ta\tb", text());
    }

    @Test
    void undoRedoRestoreExactly() throws Exception {
        load("COM\tx\r\nMTD\tkey\tv\r\nSMH\tA\tB\r\nSML\t1\tPC 34:1 with\r\n a newline\t5\r\nSML\t2\t3");
        ByteArrayOutputStream before = new ByteArrayOutputStream();
        db.writeTo(before);
        apply(new JoinWithPrevious(5), new InsertColumn("SMH", 1, "N", "f"));
        apply(new DeleteLine(2), new MoveRow(1, 4));
        apply(new SetCell(1, 1, "z"), new InsertLine(5, "MTD\tq"));
        ByteArrayOutputStream after = new ByteArrayOutputStream();
        db.writeTo(after);
        assertEquals(3, db.revision());
        assertTrue(editor.canUndo(db));
        assertFalse(editor.canRedo(db));
        assertEquals(4, editor.undo(db, 3));
        assertEquals(5, editor.undo(db, 4));
        assertEquals(6, editor.undo(db, 5));
        ByteArrayOutputStream undone = new ByteArrayOutputStream();
        db.writeTo(undone);
        assertArrayEquals(before.toByteArray(), undone.toByteArray());
        assertFalse(editor.canUndo(db));
        assertTrue(editor.canRedo(db));
        assertThrows(IllegalStateException.class, () -> editor.undo(db, 6));
        editor.redo(db, 6);
        editor.redo(db, 7);
        assertEquals(9, editor.redo(db, 8));
        ByteArrayOutputStream redone = new ByteArrayOutputStream();
        db.writeTo(redone);
        assertArrayEquals(after.toByteArray(), redone.toByteArray());
        assertFalse(editor.canRedo(db));
        assertThrows(IllegalStateException.class, () -> editor.redo(db, 9));
    }

    @Test
    void newBatchClearsRedo() {
        apply(new SetCell(4, 2, "a"));
        editor.undo(db, 1);
        assertTrue(editor.canRedo(db));
        apply(new SetCell(4, 2, "b"));
        assertFalse(editor.canRedo(db));
        assertTrue(editor.canUndo(db));
    }

    @Test
    void staleRevisionIsRefused() throws Exception {
        apply(new SetCell(4, 2, "a"));
        StaleRevisionException e = assertThrows(StaleRevisionException.class,
            () -> editor.apply(db, new EditBatch(0, List.of(new SetCell(4, 2, "b")))));
        assertEquals(1, e.expected());
        assertEquals(0, e.actual());
        assertThrows(StaleRevisionException.class, () -> editor.undo(db, 0));
        assertEquals(TEXT.replace("PC 34:1", "a"), text());
        assertEquals(1, db.revision());
    }

    @Test
    void refusesOutOfRangeAndForbiddenText() throws Exception {
        List<EditOperation> bad = List.of(new SetCell(0, 1, "x"), new SetCell(9, 1, "x"), new SetCell(4, 0, "x"),
            new SetCell(4, 9, "x"), new SetCell(4, 1, "a\tb"), new SetCell(4, 1, "a\nb"), new SetCell(4, 1, "a\rb"),
            new SetCell(4, 1, "x".repeat(100_001)), new SetRawLine(4, "a\nb"), new InsertLine(1, "a\rb"),
            new InsertLine(10, "x"), new InsertLine(0, "x"), new DeleteLine(9), new MoveRow(1, 10), new MoveRow(1, 0),
            new MoveRow(9, 1), new JoinWithPrevious(1), new SetMetadataValue(2, "a\tb"),
            new InsertColumn("SML", 1, "n", "f"), new InsertColumn("SMH", 0, "n", "f"),
            new InsertColumn("SMH", 4, "n", "f"), new InsertColumn("SMH", 1, "a\tb", "f"),
            new InsertColumn("SMH", 1, "n", "a\nb"), new DeleteColumn("SMH", "unknown"),
            new RenameColumn("SMH", "unknown", "x"), new RenameColumn("SMH", "name", "a\tb"),
            new SetRawLine(4, "x".repeat(1_000_001)), new SplitCells(4));
        for (EditOperation op : bad) {
            assertThrows(IllegalArgumentException.class, () -> apply(op), op.toString());
            assertEquals(TEXT, text(), op.toString());
            assertEquals(0, db.revision());
        }
        List<EditOperation> many = new ArrayList<>();
        for (int i = 0; i < 5001; i++) {
            many.add(new SetCell(4, 2, "x"));
        }
        assertThrows(IllegalArgumentException.class, () -> editor.apply(db, new EditBatch(0, many)));
        assertThrows(IllegalArgumentException.class, () -> editor.apply(db, new EditBatch(0, List.of())));
        assertEquals(TEXT, text());
        many.remove(0);
        assertEquals(1, editor.apply(db, new EditBatch(0, many)));
    }

    @Test
    void batchIsAtomic() throws Exception {
        assertThrows(IllegalArgumentException.class,
            () -> apply(new SetCell(4, 2, "changed"), new DeleteLine(99)));
        assertEquals(TEXT, text());
        assertEquals(0, db.revision());
        assertFalse(editor.canUndo(db));
    }

    @Test
    void jsonBatchDeserialises() throws Exception {
        String json = """
            {"revision":0,"operations":[{"op":"SetCell","line":4,"cell":2,"text":"J"},
            {"op":"InsertColumn","header":"SMH","position":1,"name":"n","fill":"f"},{"op":"DeleteLine","line":1}]}""";
        EditBatch batch = new ObjectMapper().readValue(json, EditBatch.class);
        assertEquals(new SetCell(4, 2, "J"), batch.operations().get(0));
        assertEquals(new DeleteLine(1), batch.operations().get(2));
        assertEquals(1, editor.apply(db, batch));
    }

    @Test
    void rebuiltLinesRespectTheLineLimit() throws Exception {
        List<EditOperation> ops = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ops.add(new SetCell(4, 3 + i, "x".repeat(100_000)));
        }
        assertThrows(IllegalArgumentException.class, () -> apply(ops.toArray(new EditOperation[0])));
        assertEquals(TEXT, text());
        assertEquals(0, db.revision());
    }

    @Test
    void batchOverTheBudgetIsRejectedAtomically() throws Exception {
        String big = "MTD" + ("\t" + "a".repeat(99_999)).repeat(9) + "\n";
        load(big);
        List<EditOperation> ops = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            ops.add(new SetCell(1, 1, (i % 2 == 0 ? "b" : "c").repeat(99_999)));
        }
        assertThrows(IllegalArgumentException.class, () -> apply(ops.toArray(new EditOperation[0])));
        assertEquals(big, text());
        assertEquals(0, db.revision());
        assertEquals(1, apply(ops.get(0)));
    }

    @Test
    void historyKeepsOnly100UndoableBatches() {
        for (int i = 0; i < 101; i++) {
            apply(new SetCell(4, 2, "v" + i));
        }
        int undone = 0;
        while (editor.canUndo(db)) {
            editor.undo(db, db.revision());
            undone++;
        }
        assertEquals(100, undone);
        assertEquals("SML\t1\tv0", db.line(4).orElseThrow().text());
    }

    @Test
    void identicalReplacementIsNotRecorded() {
        apply(new SetCell(4, 2, "PC 34:1"), new RenameColumn("SMH", "SML_ID", "SML_ID"),
            new SetMetadataValue(2, "v"), new MoveRow(4, 5));
        assertEquals("[]", db.peekUndo().orElseThrow().forward());
    }

    @Test
    void importResetsRevisionAndHistory() throws Exception {
        apply(new SetCell(4, 2, "a"));
        load(TEXT);
        assertEquals(0, db.revision());
        assertFalse(editor.canUndo(db));
    }

    @Test
    void mixedUndoRedoOrder() throws Exception {
        apply(new SetCell(4, 2, "one"));
        ByteArrayOutputStream s1 = new ByteArrayOutputStream();
        db.writeTo(s1);
        apply(new InsertLine(1, "MTD\tn\tm"), new DeleteLine(5));
        ByteArrayOutputStream s2 = new ByteArrayOutputStream();
        db.writeTo(s2);
        apply(new InsertColumn("SMH", 1, "N", "f"), new MoveRow(2, 6));
        ByteArrayOutputStream s3 = new ByteArrayOutputStream();
        db.writeTo(s3);
        long rev = db.revision();
        rev = editor.undo(db, rev);
        rev = editor.undo(db, rev);
        rev = editor.redo(db, rev);
        ByteArrayOutputStream now = new ByteArrayOutputStream();
        db.writeTo(now);
        assertArrayEquals(s2.toByteArray(), now.toByteArray());
        rev = editor.undo(db, rev);
        rev = editor.undo(db, rev);
        rev = editor.redo(db, rev);
        rev = editor.redo(db, rev);
        rev = editor.redo(db, rev);
        now.reset();
        db.writeTo(now);
        assertArrayEquals(s3.toByteArray(), now.toByteArray());
    }

    @Test
    void splitCellsSkipsLeadingSpaces() throws Exception {
        load("MTD\t a b\n");
        apply(new SplitCells(1));
        assertEquals(List.of("MTD", " a", "b"), db.line(1).orElseThrow().cells());
        load("MTD\t   \n");
        assertThrows(IllegalArgumentException.class, () -> apply(new SplitCells(1)));
    }

    @Test
    void budgetCountsTheSerialisedHistory() throws Exception {
        String ctl = "\u0001".repeat(99_999);
        String big = "MTD" + ("\t" + ctl).repeat(9) + "\n";
        load(big);
        List<EditOperation> ops = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            ops.add(new SetCell(1, 1, "\u0001".repeat(99_998) + (i % 2 == 0 ? "b" : "c")));
        }
        // about 36 M raw characters, but over 200 M once control characters are escaped
        assertThrows(IllegalArgumentException.class, () -> apply(ops.toArray(new EditOperation[0])));
        assertEquals(big, text());
        assertEquals(0, db.revision());
    }

    private void assertEstimateCoversStoredHistory() {
        WorkingCopyDb.HistoryEntry e = db.peekUndo().orElseThrow();
        long estimate = 0;
        for (String json : List.of(e.forward(), e.inverse())) {
            for (WorkingCopyEditor.Step s : WorkingCopyEditor.fromJson(json)) {
                estimate += WorkingCopyEditor.estimate(s);
            }
        }
        assertTrue(estimate >= e.forward().length() + e.inverse().length(),
            estimate + " < " + (e.forward().length() + e.inverse().length()));
    }

    @Test
    void estimateCoversTheStoredHistory() throws Exception {
        List<EditOperation> tiny = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            tiny.add(new InsertLine(1, "a"));
        }
        apply(tiny.toArray(new EditOperation[0]));
        assertEstimateCoversStoredHistory();
        apply(new InsertColumn("SMH", 1, "N", "f"));
        assertEstimateCoversStoredHistory();
        apply(new SetCell(305, 2, "q\"\\\u0001\u001f\"x"), new InsertLine(1, "MTD\t\"k\"\t\u0002"));
        assertEstimateCoversStoredHistory();
    }

    @Test
    void noEditGoesBeyondTheCellCap() throws Exception {
        String wide = "COM" + "\tx".repeat(CopyText.MAX_CELLS);
        String before = text();
        IllegalArgumentException raw = assertThrows(IllegalArgumentException.class,
            () -> apply(new SetRawLine(1, wide)));
        assertEquals("A line has at most 20,000 cells", raw.getMessage());
        assertThrows(IllegalArgumentException.class, () -> apply(new InsertLine(1, wide)));
        assertEquals(before, text());
        // up to the cap is fine
        apply(new SetRawLine(1, "COM" + "\tx".repeat(CopyText.MAX_CELLS - 1)));
        assertEquals(CopyText.MAX_CELLS, db.line(1).orElseThrow().cells().size());
    }
}
