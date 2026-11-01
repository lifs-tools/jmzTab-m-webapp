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
package org.lifstools.mztab.validator.webapp.workingcopy.quickfix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lifstools.mztab.validator.engine.api.ValidationLocation;
import org.lifstools.mztab.validator.engine.api.ValidationMessage;
import org.lifstools.mztab.validator.webapp.workingcopy.CopyText;
import org.lifstools.mztab.validator.webapp.workingcopy.EditBatch;
import org.lifstools.mztab.validator.webapp.workingcopy.EditOperation;
import org.lifstools.mztab.validator.webapp.workingcopy.EditOperation.*;
import org.lifstools.mztab.validator.webapp.workingcopy.SourceEncoding;
import org.lifstools.mztab.validator.webapp.workingcopy.WorkingCopyDb;
import org.lifstools.mztab.validator.webapp.workingcopy.WorkingCopyEditor;

/** The fixes on small synthetic copies, without an engine: messages are built by hand. */
class QuickFixesTest {

    @TempDir
    Path dir;
    WorkingCopyDb db;

    @BeforeEach
    void setUp() {
        db = WorkingCopyDb.open(dir.resolve("q.db"));
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private void load(String text) throws IOException {
        db.importLines(CopyText.read(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),
            SourceEncoding.UTF_8));
    }

    private void loadResource(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/workingcopy/" + name)) {
            load(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static ValidationMessage message(String code, String text, long line) {
        ValidationMessage m = new ValidationMessage();
        m.setCode("[" + code + "] line " + line + ": " + text);
        m.setMessage(text);
        m.setLineNumber(line);
        return m;
    }

    private static QuickFix fix(String id) {
        return QuickFixes.find(id).orElseThrow();
    }

    private List<EditOperation> ops(String id, ValidationMessage m) {
        return fix(id).forMessage(m, db).orElseThrow();
    }

    private String apply(List<EditOperation> ops) throws IOException {
        new WorkingCopyEditor().apply(db, new EditBatch(db.revision(), ops));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        db.writeTo(out);
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void idsCoverTheFixesAndLatin1() {
        assertTrue(QuickFixes.ids().containsAll(List.of("remove-empty-parameter", "normalise-doi", "join-broken-line",
            "split-mtd-line", "add-mandatory-column", "add-missing-metadata", "remove-unused-cv",
            "remove-mismatched-field",
            "reencode-latin1")));
        assertTrue(QuickFixes.find("reencode-latin1").isEmpty());
        assertTrue(QuickFixes.find("no-such-fix").isEmpty());
    }

    /** The documentation names fixes by their titles (frontend core/quick-fix-labels.ts, QUICK_FIX_TITLES). */
    @Test
    void theDocumentationNamesTheFixesByTheirTitles() throws IOException {
        // surefire sets basedir to the module directory; the frontend is next to it
        String base = System.getProperty("basedir", System.getProperty("user.dir"));
        String labels = Files.readString(Path.of(base, "..", "frontend", "projects", "webapp", "src", "app", "core",
            "quick-fix-labels.ts"));
        String fixId = "remove-mismatched-field";
        assertTrue(labels.contains("'" + fixId + "': '" + fix(fixId).title() + "'"), labels);
    }

    @Test
    void removeEmptyParameter() throws Exception {
        loadResource("empty-parameter.mztab");
        assertEquals(List.of(new DeleteLine(3)), ops("remove-empty-parameter",
            message("Error-1012", "\"x\" value \"[, , , ]\" is not a valid parameter.", 3)));
        assertEquals(List.of(new DeleteLine(5)), ops("remove-empty-parameter",
            message("Error-1013", "\"x\" value \"[, , , ] | [,,,]\" is not a valid list of parameters.", 5)));
        assertTrue(fix("remove-empty-parameter").appliesToAll());
        assertEquals("MTD\tmzTab-version\t2.0.0-M\nMTD\tmzTab-ID\tQF-1\nMTD\tinstrument[1]-source\t[MS, MS:1, , ]\n"
            + "MTD\tinstrument[2]-detector\t[, , , ] | [,,,]\nMTD\tdescription\t[, , , ]x\n",
            apply(ops("remove-empty-parameter",
                message("Error-1012", "\"x\" value \"[, , , ]\" is not a valid parameter.", 3))));
    }

    private static final String MISMATCH = "\"%s\" is present but the profile \"M+S\" has no \"small molecule"
        + " feature (SMF)\" section that would require it.";

    private static final String PROFILE_FILE = "MTD\tmzTab-version\t2.1.0-M\n"
        + "MTD\tmzTab-profile\tM+S\n"
        + "MTD\tsmall_molecule-quantification_unit\t[UO, UO:1, a, ]\n"
        + "MTD\tsmall_molecule_feature-quantification_unit\t[UO, UO:1, a, ]\n"
        + "MTD\tcolunit-small_molecule_feature\tx=[UO, UO:1, a, ]\n"
        + "SMH\tSML_ID\tfoo\tbar\n"
        + "SML\t1\ta\tb\n"
        + "SML\t2\tc\td\n"
        + "SEH\tSME_ID\tfoo\n"
        + "SME\t1\te\n";

    private static ValidationMessage mismatch(String name) {
        return message("Info-2057", String.format(MISMATCH, name), -1);
    }

    @Test
    void removeMismatchedFieldDeletesTheMetadataLine() throws Exception {
        load(PROFILE_FILE);
        QuickFixPreview p = QuickFixes.preview(fix("remove-mismatched-field"), 0,
            mismatch("small_molecule_feature-quantification_unit"), null, db);
        assertEquals(List.of(new DeleteLine(4)), p.batch().operations());
        assertEquals(1, p.changes().size());
        assertEquals(4, p.changes().get(0).lineNumber());
        assertNull(p.changes().get(0).after());
        assertTrue(fix("remove-mismatched-field").appliesToAll());
        assertFalse(apply(p.batch().operations()).contains("small_molecule_feature-quantification_unit"));
    }

    @Test
    void removeMismatchedFieldDeletesTheNamedColumn() throws Exception {
        load(PROFILE_FILE);
        QuickFixPreview p = QuickFixes.preview(fix("remove-mismatched-field"), 0, mismatch("bar"), null, db);
        assertEquals(List.of(new DeleteColumn("SMH", "bar", 3)), p.batch().operations());
        assertEquals(3, p.changes().size());
        assertEquals("SMH\tSML_ID\tfoo", p.changes().get(0).after());
        String text = apply(p.batch().operations());
        assertTrue(text.contains("SMH\tSML_ID\tfoo\nSML\t1\ta\nSML\t2\tc\n"), text);
    }

    @Test
    void removeMismatchedFieldIsNotOfferedWhenTheNameIsNotFoundOrNotUnique() throws Exception {
        load(PROFILE_FILE.replace("SEH\tSME_ID\tfoo", "SEH\tSME_ID\tfoo\tbar"));
        // no such key or column
        assertTrue(fix("remove-mismatched-field").forMessage(mismatch("nothing_here"), db).isEmpty());
        // "bar" is a column of two tables: the message does not say which
        assertTrue(fix("remove-mismatched-field").forMessage(mismatch("bar"), db).isEmpty());
        // not a 2057
        assertTrue(fix("remove-mismatched-field").forMessage(
            message("Info-2058", String.format(MISMATCH, "foo"), -1), db).isEmpty());
        // not text that names a field
        assertTrue(fix("remove-mismatched-field").forMessage(message("Info-2057", "something else", -1), db).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> QuickFixes.preview(fix("remove-mismatched-field"), 0,
            mismatch("bar"), null, db));
    }

    @Test
    void removeMismatchedFieldForAllMessagesIsOneOperationEachFromTheBottomUp() throws Exception {
        load(PROFILE_FILE);
        List<ValidationMessage> group = List.of(mismatch("foo"), mismatch("small_molecule-quantification_unit"),
            mismatch("bar"), mismatch("small_molecule_feature-quantification_unit"),
            mismatch("small_molecule_feature-quantification_unit"), mismatch("colunit-small_molecule_feature"));
        QuickFixPreview p = QuickFixes.preview(fix("remove-mismatched-field"), 0, group.get(3), group, db);
        // "foo" occurs in two tables, so it is skipped; the repeated message makes one operation
        assertEquals(4, p.occurrences());
        assertEquals(List.of(new DeleteLine(5), new DeleteLine(4), new DeleteLine(3), new DeleteColumn("SMH", "bar", 3)),
            p.batch().operations());
        String text = apply(p.batch().operations());
        assertEquals("MTD\tmzTab-version\t2.1.0-M\nMTD\tmzTab-profile\tM+S\nSMH\tSML_ID\tfoo\n"
            + "SML\t1\ta\nSML\t2\tc\nSEH\tSME_ID\tfoo\nSME\t1\te\n", text);
        assertEquals(3 + 3, p.changes().size());
    }

    @Test
    void removeMismatchedFieldForAllMixesALineAndAColumn() throws Exception {
        load(PROFILE_FILE);
        List<ValidationMessage> group = List.of(mismatch("bar"), mismatch("small_molecule_feature-quantification_unit"));
        QuickFixPreview p = QuickFixes.preview(fix("remove-mismatched-field"), 0, group.get(0), group, db);
        assertEquals(2, p.occurrences());
        // the line delete first, the column delete (which keeps the line numbers) last
        assertEquals(List.of(new DeleteLine(4), new DeleteColumn("SMH", "bar", 3)), p.batch().operations());
        assertEquals(4, p.changes().size());
        assertEquals("MTD\tmzTab-version\t2.1.0-M\nMTD\tmzTab-profile\tM+S\n"
            + "MTD\tsmall_molecule-quantification_unit\t[UO, UO:1, a, ]\n"
            + "MTD\tcolunit-small_molecule_feature\tx=[UO, UO:1, a, ]\n"
            + "SMH\tSML_ID\tfoo\nSML\t1\ta\nSML\t2\tc\nSEH\tSME_ID\tfoo\nSME\t1\te\n",
            apply(p.batch().operations()));
    }

    @Test
    void removeMismatchedFieldIsWithheldWhenAKeyAndAColumnShareTheName() throws Exception {
        load("MTD\tmzTab-version\t2.1.0-M\nMTD\tshared\tv\nSMH\tSML_ID\tshared\nSML\t1\tx\n");
        assertTrue(fix("remove-mismatched-field").forMessage(mismatch("shared"), db).isEmpty());
    }

    private static final String GROUP_FILE = "MTD\tmzTab-version\t2.1.0-M\n"
        + "MTD\tmzTab-profile\tM+S\n"
        + "MTD\tdatabase[1]\t[,, Pubchem, ]\n"
        + "MTD\tdatabase[1]-prefix\tPUBCHEM\n"
        + "MTD\tid_confidence_measure[1]\t[MS, MS:1002890, fragmentation score, ]\n"
        + "MTD\tdatabase[2]\t[,, LipidMaps, ]\n"
        + "MTD\tdatabase[2]-uri\thttp://x\n"
        + "MTD\tdatabase_x\ty\n"
        + "MTD\tquantification_method\t[MS, MS:1, q, ]\n"
        + "SMH\tSML_ID\tdatabase_identifier\n"
        + "SML\t1\tx\n";

    @Test
    void removeMismatchedGroupDeletesEveryLineOfTheGroupFromTheBottomUp() throws Exception {
        load(GROUP_FILE);
        QuickFixPreview p = QuickFixes.preview(fix("remove-mismatched-field"), 0, mismatch("database"), null, db);
        assertEquals(List.of(new DeleteLine(7), new DeleteLine(6), new DeleteLine(4), new DeleteLine(3)),
            p.batch().operations());
        assertEquals(4, p.changes().size());
        assertEquals("MTD\tmzTab-version\t2.1.0-M\nMTD\tmzTab-profile\tM+S\n"
            + "MTD\tid_confidence_measure[1]\t[MS, MS:1002890, fragmentation score, ]\n"
            + "MTD\tdatabase_x\ty\nMTD\tquantification_method\t[MS, MS:1, q, ]\n"
            + "SMH\tSML_ID\tdatabase_identifier\nSML\t1\tx\n", apply(p.batch().operations()));
    }

    @Test
    void removeMismatchedGroupIsWithheldWhenTheNameIsAlsoAKey() throws Exception {
        load(GROUP_FILE.replace("MTD\tdatabase_x\ty", "MTD\tdatabase\ty"));
        assertTrue(fix("remove-mismatched-field").forMessage(mismatch("database"), db).isEmpty());
    }

    @Test
    void removeMismatchedGroupIsWithheldWhenTheNameIsAlsoAColumn() throws Exception {
        load("MTD\tmzTab-version\t2.1.0-M\nMTD\tdatabase[1]\tx\nSMH\tSML_ID\tdatabase\nSML\t1\tx\n");
        assertTrue(fix("remove-mismatched-field").forMessage(mismatch("database"), db).isEmpty());
    }

    @Test
    void removeMismatchedForAllMergesGroupsAndKeysFromTheBottomUp() throws Exception {
        // the groups interleave: every delete must still name the line it meant
        load(GROUP_FILE);
        List<ValidationMessage> group = List.of(mismatch("id_confidence_measure"), mismatch("database"),
            mismatch("quantification_method"));
        QuickFixPreview p = QuickFixes.preview(fix("remove-mismatched-field"), 0, group.get(1), group, db);
        assertEquals(3, p.occurrences());
        assertEquals(List.of(new DeleteLine(9), new DeleteLine(7), new DeleteLine(6), new DeleteLine(5),
            new DeleteLine(4), new DeleteLine(3)), p.batch().operations());
        assertEquals("MTD\tmzTab-version\t2.1.0-M\nMTD\tmzTab-profile\tM+S\nMTD\tdatabase_x\ty\n"
            + "SMH\tSML_ID\tdatabase_identifier\nSML\t1\tx\n", apply(p.batch().operations()));
    }

    @Test
    void removeMismatchedFieldUsesTheFirstHeaderLineOfATable() throws Exception {
        // the engine locates the message at the first SMH line, so the fix removes the column there
        load("MTD\tmzTab-version\t2.1.0-M\nSMH\tSML_ID\ta\tb\nSML\t1\tx\ty\nSMH\tSML_ID\tb\ta\nSML\t2\tz\tw\n");
        assertEquals(List.of(new DeleteColumn("SMH", "b", 3)),
            fix("remove-mismatched-field").forMessage(mismatch("b"), db).orElseThrow());
    }

    @Test
    void removeSeveralMismatchedColumnsOfOneTableRightToLeft() throws Exception {
        load("MTD\tmzTab-version\t2.1.0-M\nSMH\tSML_ID\ta\tb\tc\nSML\t1\tx\ty\tz\n");
        List<ValidationMessage> group = List.of(mismatch("a"), mismatch("c"));
        QuickFixPreview p = QuickFixes.preview(fix("remove-mismatched-field"), 0, group.get(0), group, db);
        assertEquals(List.of(new DeleteColumn("SMH", "c", 4), new DeleteColumn("SMH", "a", 2)),
            p.batch().operations());
        assertEquals(2, p.changes().size());
        assertEquals("SMH\tSML_ID\tb", p.changes().get(0).after());
        assertEquals("SML\t1\ty", p.changes().get(1).after());
        assertEquals("MTD\tmzTab-version\t2.1.0-M\nSMH\tSML_ID\tb\nSML\t1\ty\n", apply(p.batch().operations()));
    }

    @Test
    void normaliseDoi() throws Exception {
        loadResource("doi.mztab");
        List<EditOperation> ops = ops("normalise-doi", message("Error-1014",
            "\"publication[1]\" value \"x\" is not a valid publication.", 2));
        assertEquals(List.of(new SetMetadataValue(2, "doi:10.1/x|doi:10.2/y|pubmed:123")), ops);
        assertEquals(List.of(new SetMetadataValue(4, "doi:10.4/w")), ops("normalise-doi",
            message("Error-1014", "\"publication[3]\" value \"x\" is not a valid publication.", 4)));
        assertTrue(fix("normalise-doi").forMessage(message("Error-1014", "x", 3), db).isEmpty(),
            "already in the expected form: nothing to do");
        assertEquals("doi:10.1/x|doi:10.2/y|pubmed:123", apply(ops).lines().skip(1).findFirst().orElseThrow()
            .split("\t")[2]);
    }

    @Test
    void joinBrokenLine() throws Exception {
        loadResource("broken-line.mztab");
        // line 3 continues line 2 and gets a space; line 5 continues the MTD line before it
        List<EditOperation> ops = ops("join-broken-line", message("Error-1000", "\"second part\" MUST start with", 3));
        assertEquals(List.of(new SetRawLine(3, " second part"), new JoinWithPrevious(3)), ops);
        // the second line already starts with a space: joined as is
        assertEquals(List.of(new JoinWithPrevious(6)), ops("join-broken-line",
            message("Error-1000", "\" fourth\" MUST start with", 6)));
        assertEquals("MTD\tmzTab-version\t2.0.0-M\nMTD\tdescription\tfirst part second part\nMTD\tmzTab-ID\tQF-3\n"
            + "third\n fourth\n", apply(ops));
    }

    @Test
    void joinNeedsAPreviousLine() throws Exception {
        load("orphan\nMTD\ta\tb\n");
        assertTrue(fix("join-broken-line").forMessage(message("Error-1000", "x", 1), db).isEmpty());
        assertTrue(fix("join-broken-line").forMessage(message("Error-1000", "x", 2), db).isEmpty(),
            "a metadata line is not a continuation");
        assertTrue(fix("join-broken-line").forMessage(message("Error-1000", "x", 9), db).isEmpty());
    }

    @Test
    void splitMtdLine() throws Exception {
        loadResource("mtd-spaces.mztab");
        List<EditOperation> ops = ops("split-mtd-line", message("Error-1008", "\"MTD\tmzTab-ID QF-4\" should have", 2));
        assertEquals(List.of(new SplitCells(2)), ops);
        assertTrue(fix("split-mtd-line").forMessage(message("Error-1008", "x", 3), db).isEmpty(), "three cells");
        assertTrue(fix("split-mtd-line").forMessage(message("Error-1008", "x", 5), db).isEmpty(), "no space");
        assertEquals(List.of(new SplitCells(4)), ops("split-mtd-line", message("Error-1008", "x", 4)),
            "a run of spaces");
        assertEquals("MTD\tmzTab-version\t2.0.0-M\nMTD\tmzTab-ID\tQF-4\nMTD\tdescription\ta text with spaces\n"
            + "MTD\ttitle  two  spaces\nMTD\tnospace\n", apply(ops));
    }

    @Test
    void addMandatoryColumnBeforeAbundances() throws Exception {
        loadResource("missing-column.mztab");
        ValidationMessage m = message("Error-1004", "Mandatory column \"smiles2\" is required on the header line.", 2);
        assertEquals(List.of(new InsertColumn("SMH", 3, "smiles2", "null")), ops("add-mandatory-column", m));
        // the location's column wins over the text
        ValidationMessage located = message("Error-1004", "Mandatory column \"other\" is required.", 2);
        ValidationLocation loc = new ValidationLocation();
        loc.setColumn("chemical_name");
        located.setLocation(loc);
        assertEquals(List.of(new InsertColumn("SMH", 3, "chemical_name", "null")),
            ops("add-mandatory-column", located));
        // no abundance or opt column: at the end
        assertEquals(List.of(new InsertColumn("SFH", 3, "x_col", "null")), ops("add-mandatory-column",
            message("Error-1004", "Mandatory column \"x_col\" is required on the header line.", 5)));
        assertTrue(fix("add-mandatory-column").forMessage(message("Error-1004",
            "Mandatory column \"smiles\" is required on the header line.", 2), db).isEmpty(), "already there");
        assertTrue(fix("add-mandatory-column").forMessage(message("Error-1004",
            "Mandatory column \"q\" is required on the header line.", 3), db).isEmpty(), "line 3 is no header");
        assertFalse(fix("add-mandatory-column").appliesToAll());
        assertEquals("SMH\tSML_ID\tsmiles\tsmiles2\tabundance_assay[1]\topt_global_x",
            apply(ops("add-mandatory-column", m)).lines().skip(1).findFirst().orElseThrow());
        assertEquals("SML\t1\ts1\tnull\t5\tx", db.line(3).orElseThrow().text());
    }

    @Test
    void addMissingMetadataAfterItsGroup() throws Exception {
        loadResource("missing-metadata.mztab");
        // sample[1] is undefined: sample[2] lines exist, so it goes after the last of them (line 6)
        assertEquals(List.of(new InsertLine(7, "MTD\tsample[1]\t")), ops("add-missing-metadata",
            message("Error-2008", "\"sample[1]\" MUST be defined in the metadata section.", 4)));
        // no ms_run... group for a new name: after the last MTD line (line 7)
        assertEquals(List.of(new InsertLine(8, "MTD\ttitle\t")), ops("add-missing-metadata",
            message("Error-2008", "\"title\" MUST be defined in the metadata section.", 1)));
        // the group of an assay is the assay lines
        assertEquals(List.of(new InsertLine(5, "MTD\tassay[2]\t")), ops("add-missing-metadata",
            message("Error-2008", "\"assay[2]\" MUST be defined in the metadata section.", 3)));
        assertTrue(fix("add-missing-metadata").forMessage(message("Error-2008", "something else", 3), db).isEmpty());
        assertEquals("MTD\tmzTab-version\t2.0.0-M\nMTD\tmzTab-ID\tQF-6\nMTD\tassay[1]\td\n"
            + "MTD\tassay[1]-sample_ref\tsample[1]\nMTD\tsample[2]\tb\nMTD\tsample[2]-description\tc\n"
            + "MTD\tsample[1]\t\nMTD\tms_run[1]-location\tfile:///x\nSMH\tSML_ID\nSML\t1\n",
            apply(ops("add-missing-metadata",
                message("Error-2008", "\"sample[1]\" MUST be defined in the metadata section.", 4))));
    }

    @Test
    void removeUnusedCvRenumbers() throws Exception {
        loadResource("unused-cv.mztab");
        List<EditOperation> ops = ops("remove-unused-cv", message("Warn-3014",
            "The CV with prefix \"UO\" was defined but never used. ", 0));
        assertEquals(List.of(new RenameMetadataKey(7, "cv[2]-label"), new RenameMetadataKey(8, "cv[2]-uri"),
            new RenameMetadataKey(9, "cv[2]-version"), new DeleteLine(6), new DeleteLine(5), new DeleteLine(4)), ops);
        assertEquals("MTD\tmzTab-version\t2.0.0-M\nMTD\tcv[1]-label\tMS\nMTD\tcv[1]-uri\thttp://ms\n"
            + "MTD\tcv[2]-label\tNCIT\nMTD\tcv[2]-uri\thttp://ncit\nMTD\tcv[2]-version\t1\nMTD\tdescription\td\n",
            apply(ops));
    }

    @Test
    void removeUnusedLastCvNeedsNoRenames() throws Exception {
        loadResource("unused-cv.mztab");
        assertEquals(List.of(new DeleteLine(9), new DeleteLine(8), new DeleteLine(7)), ops("remove-unused-cv",
            message("Warn-3014", "The CV with prefix \"NCIT\" was defined but never used. ", 0)));
        assertTrue(fix("remove-unused-cv").forMessage(message("Warn-3014",
            "The CV with prefix \"GO\" was defined but never used. ", 0), db).isEmpty(), "no such label");
    }

    @Test
    void noFixWhenThePatternDoesNotMatch() throws Exception {
        loadResource("empty-parameter.mztab");
        // line 4 holds "[MS, MS:1, , ]": not empty
        assertEquals(java.util.Optional.empty(), fix("remove-empty-parameter").forMessage(
            message("Error-1012", "\"x\" value \"[MS, MS:1, , ]\" is not a valid parameter.", 4), db));
        assertTrue(fix("remove-empty-parameter").forMessage(
            message("Error-1012", "\"x\" value \"[, , , ]\" is not a valid parameter.", 4), db).isEmpty(),
            "the message says empty but the line does not");
        assertTrue(fix("remove-empty-parameter").forMessage(
            message("Error-1012", "\"x\" value \"[, , , ]x\" is not a valid parameter.", 6), db).isEmpty());
        assertTrue(fix("remove-empty-parameter").forMessage(
            message("Error-1012", "\"x\" value \"[, , , ]\" is not a valid parameter.", 99), db).isEmpty(),
            "no such line");
        ValidationMessage noLine = message("Error-1012", "\"x\" value \"[, , , ]\" is not a valid parameter.", 3);
        noLine.setLineNumber(null);
        assertTrue(fix("remove-empty-parameter").forMessage(noLine, db).isEmpty());
    }

    private void load270() throws IOException {
        StringBuilder text = new StringBuilder("MTD\tmzTab-version\t2.0.0-M\n");
        for (int i = 0; i < 270; i++) {
            text.append("MTD\tsample[1]-custom[").append(i + 1).append("]\t[, , , ]\n");
        }
        text.append("MTD\tmzTab-ID\tx\n");
        load(text.toString());
    }

    @Test
    void allCoversEveryOccurrence() throws Exception {
        load270();
        List<ValidationMessage> group = new ArrayList<>();
        for (int i = 0; i < 270; i++) {
            group.add(message("Error-1012", "\"x\" value \"[, , , ]\" is not a valid parameter.", i + 2));
        }
        // the same line twice is one occurrence
        group.add(group.get(0));

        QuickFixPreview p = QuickFixes.preview(fix("remove-empty-parameter"), 0, group.get(5), group, db);

        assertEquals(270, p.occurrences());
        assertEquals(200, p.changes().size());
        assertEquals(270, p.batch().operations().size());
        assertEquals(0, p.revision());
        assertEquals(0, p.batch().revision());
        assertEquals(new LineChange(2, "MTD\tsample[1]-custom[1]\t[, , , ]", null), p.changes().get(0));
        assertEquals(new DeleteLine(271), p.batch().operations().get(0), "from the bottom up");
        // applying it removes exactly those lines
        new WorkingCopyEditor().apply(db, p.batch());
        assertEquals(2, db.lineCount());
    }

    @Test
    void previewOfOneMessageAndNoWrites() throws Exception {
        loadResource("empty-parameter.mztab");
        ValidationMessage m = message("Error-1012", "\"x\" value \"[, , , ]\" is not a valid parameter.", 3);
        long revision = db.revision();
        QuickFixPreview p = QuickFixes.preview(fix("remove-empty-parameter"), revision, m, null, db);
        assertEquals("remove-empty-parameter", p.fixId());
        assertEquals(1, p.occurrences());
        assertEquals(List.of(new LineChange(3, "MTD\tinstrument[1]-detector\t[, , , ]", null)), p.changes());
        assertEquals(revision, db.revision());
        assertEquals(6, db.lineCount());
        assertFalse(new WorkingCopyEditor().canUndo(db));
    }

    @Test
    void previewsShowBeforeAndAfter() throws Exception {
        loadResource("broken-line.mztab");
        QuickFixPreview join = QuickFixes.preview(fix("join-broken-line"), 0,
            message("Error-1000", "x", 3), null, db);
        assertEquals(List.of(new LineChange(2, "MTD\tdescription\tfirst part", "MTD\tdescription\tfirst part second part"),
            new LineChange(3, "second part", null)), join.changes());

        loadResource("doi.mztab");
        QuickFixPreview doi = QuickFixes.preview(fix("normalise-doi"), 0, message("Error-1014", "x", 2), null, db);
        assertEquals(List.of(new LineChange(2, "MTD\tpublication[1]\tdoi:https://doi.org/10.1/x|doi:doi:10.2/y|pubmed:123",
            "MTD\tpublication[1]\tdoi:10.1/x|doi:10.2/y|pubmed:123")), doi.changes());

        loadResource("missing-metadata.mztab");
        QuickFixPreview add = QuickFixes.preview(fix("add-missing-metadata"), 0, message("Error-2008",
            "\"sample[1]\" MUST be defined in the metadata section.", 4), null, db);
        assertEquals(List.of(new LineChange(7, null, "MTD\tsample[1]\t")), add.changes());
        assertNull(add.changes().get(0).before());

        loadResource("missing-column.mztab");
        QuickFixPreview column = QuickFixes.preview(fix("add-mandatory-column"), 0, message("Error-1004",
            "Mandatory column \"smiles2\" is required on the header line.", 2), null, db);
        assertEquals(3, column.changes().size());
        assertEquals(new LineChange(3, "SML\t1\ts1\t5\tx", "SML\t1\ts1\tnull\t5\tx"), column.changes().get(1));

        loadResource("unused-cv.mztab");
        QuickFixPreview cv = QuickFixes.preview(fix("remove-unused-cv"), 0, message("Warn-3014",
            "The CV with prefix \"UO\" was defined but never used. ", 0), null, db);
        assertEquals(6, cv.changes().size());
        assertEquals(new LineChange(7, "MTD\tcv[3]-label\tNCIT", "MTD\tcv[2]-label\tNCIT"), cv.changes().get(3));
        assertEquals(new LineChange(4, "MTD\tcv[2]-label\tUO", null), cv.changes().get(0));

        loadResource("mtd-spaces.mztab");
        QuickFixPreview split = QuickFixes.preview(fix("split-mtd-line"), 0, message("Error-1008", "x", 2), null, db);
        assertEquals(List.of(new LineChange(2, "MTD\tmzTab-ID QF-4", "MTD\tmzTab-ID\tQF-4")), split.changes());
    }

    @Test
    void refusesWhatDoesNotApply() throws Exception {
        loadResource("empty-parameter.mztab");
        ValidationMessage ok = message("Error-1012", "\"x\" value \"[, , , ]\" is not a valid parameter.", 3);
        ValidationMessage no = message("Error-1012", "\"x\" value \"[MS, MS:1, , ]\" is not a valid parameter.", 4);
        assertThrows(IllegalArgumentException.class,
            () -> QuickFixes.preview(fix("remove-empty-parameter"), 0, no, null, db));
        assertThrows(IllegalArgumentException.class,
            () -> QuickFixes.preview(fix("remove-empty-parameter"), 0, no, List.of(ok, no), db));
        // a fix for one message at a time cannot take a group
        loadResource("unused-cv.mztab");
        ValidationMessage cv = message("Warn-3014", "The CV with prefix \"UO\" was defined but never used. ", 0);
        assertThrows(IllegalArgumentException.class,
            () -> QuickFixes.preview(fix("remove-unused-cv"), 0, cv, List.of(cv), db));
    }

    @Test
    void refusesMoreThanTheBatchLimit() throws Exception {
        StringBuilder text = new StringBuilder("MTD\tmzTab-version\t2.0.0-M\n");
        for (int i = 0; i < 5001; i++) {
            text.append("MTD\tsample[1]-custom[").append(i + 1).append("]\t[, , , ]\n");
        }
        load(text.toString());
        List<ValidationMessage> group = new ArrayList<>();
        for (int i = 0; i < 5001; i++) {
            group.add(message("Error-1012", "\"x\" value \"[, , , ]\" is not a valid parameter.", i + 2));
        }
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> QuickFixes.preview(fix("remove-empty-parameter"), 0, group.get(0), group, db));
        assertTrue(e.getMessage().contains("5001"), e.getMessage());
        assertTrue(e.getMessage().contains("tool that wrote the file"), e.getMessage());
        // exactly the limit is fine
        QuickFixPreview p = QuickFixes.preview(fix("remove-empty-parameter"), 0, group.get(0),
            group.subList(0, 5000), db);
        assertEquals(5000, p.batch().operations().size());
    }

    @Test
    void aFixOnlyAppliesToItsOwnCode() throws Exception {
        // the right text on the right line, but another code
        loadResource("empty-parameter.mztab");
        assertTrue(fix("remove-empty-parameter").forMessage(
            message("Error-1014", "\"x\" value \"[, , , ]\" is not a valid parameter.", 3), db).isEmpty());
        assertTrue(fix("remove-empty-parameter").forMessage(
            message("Warn-1012", "\"x\" value \"[, , , ]\" is not a valid parameter.", 3), db).isPresent(),
            "the level does not matter");
        loadResource("doi.mztab");
        assertTrue(fix("normalise-doi").forMessage(message("Error-1012", "x", 2), db).isEmpty());
        loadResource("broken-line.mztab");
        assertTrue(fix("join-broken-line").forMessage(message("Error-1008", "x", 3), db).isEmpty());
        loadResource("mtd-spaces.mztab");
        assertTrue(fix("split-mtd-line").forMessage(message("Error-1000", "x", 2), db).isEmpty());
        loadResource("missing-column.mztab");
        assertTrue(fix("add-mandatory-column").forMessage(message("Error-1008",
            "Mandatory column \"smiles2\" is required on the header line.", 2), db).isEmpty());
        loadResource("missing-metadata.mztab");
        assertTrue(fix("add-missing-metadata").forMessage(message("Error-1004",
            "\"sample[1]\" MUST be defined in the metadata section.", 4), db).isEmpty());
        loadResource("unused-cv.mztab");
        ValidationMessage cv = message("Error-2008", "The CV with prefix \"UO\" was defined but never used. ", 0);
        assertTrue(fix("remove-unused-cv").forMessage(cv, db).isEmpty());
        assertTrue(fix("remove-unused-cv").forMessage(message("Error-3014",
            "The CV with prefix \"UO\" was defined but never used. ", 0), db).isPresent());
        ValidationMessage noCode = new ValidationMessage();
        noCode.setMessage("The CV with prefix \"UO\" was defined but never used. ");
        assertTrue(fix("remove-unused-cv").forMessage(noCode, db).isEmpty());
    }

    @Test
    void allJoinsAdjacentBrokenLinesBottomUp() throws Exception {
        // one row split into three lines
        load("MTD\tmzTab-version\t2.0.0-M\nMTD\tdescription\tone\ntwo\nthree\nMTD\tmzTab-ID\tx\n");
        List<ValidationMessage> group = List.of(message("Error-1000", "two", 3), message("Error-1000", "three", 4));

        QuickFixPreview p = QuickFixes.preview(fix("join-broken-line"), 0, group.get(0), group, db);

        assertEquals(2, p.occurrences());
        assertEquals(List.of(new SetRawLine(4, " three"), new SetRawLine(3, " two"), new JoinWithPrevious(4),
            new JoinWithPrevious(3)), p.batch().operations());
        assertEquals(List.of(new LineChange(2, "MTD\tdescription\tone", "MTD\tdescription\tone two three"),
            new LineChange(3, "two", null), new LineChange(4, "three", null)), p.changes());
        assertEquals("MTD\tmzTab-version\t2.0.0-M\nMTD\tdescription\tone two three\nMTD\tmzTab-ID\tx\n",
            apply(p.batch().operations()));
    }
}
