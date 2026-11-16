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
package org.lifstools.mztab.validator.engine.fieldrules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lifstools.mztab.validator.engine.api.FieldRule;
import org.lifstools.mztab.validator.engine.api.FieldRules;
import org.lifstools.mztab.validator.engine.api.SkeletonRequest;
import org.lifstools.mztab.validator.engine.skeleton.SkeletonWriter;

/**
 * Reads the rules of the CV mapping jmzTab-m bundles with the engine; the default profile of the engine's line is a
 * copy of it (CatalogCopyTest). Tests of the 2.0 mapping run only in an engine that serves 2.0.0-M.
 */
class FieldRuleReaderTest {

    private static final String BUNDLED = "/mappings/mzTab-M-mapping.xml";
    private static final String EXAMPLE = "/submission-vocabulary/examples/v3-example/expected/LCS-99999-01.mzTab";
    private static final String QUANTIFICATION_METHOD = "/metadata/@quantificationMethod";

    @TempDir
    Path dir;

    @Test
    void defaultTwoZeroRules() throws IOException {
        Assumptions.assumeTrue(servesTwoZero(), "the rules of the 2.0 mapping");
        Path mapping = file("mapping.xml", resource(BUNDLED));
        FieldRules rules = FieldRuleReader.read(mapping, null);

        long inXml = Pattern.compile("<CvMappingRule\\s").matcher(
            new String(resource(BUNDLED), StandardCharsets.UTF_8)).results().count();
        assertEquals(inXml, rules.rules().size());
        assertTrue(inXml > 0);
        assertFalse(rules.parsed());
        rules.rules().forEach(r -> assertNull(r.present()));
        FieldRule quantification = byId(rules, "quantification_method_must");
        assertEquals(QUANTIFICATION_METHOD, quantification.path());
        assertEquals("MUST", quantification.requirement());
        assertEquals("OR", quantification.logic());
        assertEquals("quantification_method_must", quantification.name());
        assertEquals(2, quantification.terms().size());
        assertEquals("PRIDE:0000307", quantification.terms().get(0).accession());
        assertEquals("Quantification method", quantification.terms().get(0).name());
        assertTrue(quantification.terms().get(0).allowChildren());
        assertFalse(quantification.terms().get(0).useTerm());
    }

    @Test
    void presenceFromText() throws IOException {
        Assumptions.assumeTrue(servesTwoZero(), "the 2.0 mapping and example");
        Path mapping = file("mapping.xml", resource(BUNDLED));
        String example = new String(resource(EXAMPLE), StandardCharsets.UTF_8);
        String without = example.replaceAll("(?m)^MTD\\tquantification_method\\t[^\\n]*\\n", "");
        assertTrue(!without.equals(example), "the fixture has a quantification_method line");

        FieldRules present = FieldRuleReader.read(mapping, file("with.mzTab", example.getBytes(StandardCharsets.UTF_8)));
        assertTrue(present.parsed());
        assertEquals(Boolean.TRUE, byPath(present, QUANTIFICATION_METHOD).present());

        FieldRules absent = FieldRuleReader.read(mapping, file("without.mzTab", without.getBytes(StandardCharsets.UTF_8)));
        assertTrue(absent.parsed());
        assertEquals(Boolean.FALSE, byPath(absent, QUANTIFICATION_METHOD).present());
        absent.rules().forEach(r -> assertNotNull(r.present()));
    }

    @Test
    void aRuleOfATableWithoutRowsHasNothingToApplyTo() throws IOException {
        Assumptions.assumeTrue(servesTwoZero(), "the 2.0 mapping and example");
        Path mapping = file("mapping.xml", resource(BUNDLED));
        String example = new String(resource(EXAMPLE), StandardCharsets.UTF_8);
        assertFalse(example.contains("\nSME\t"), "the fixture has no evidence rows");

        // no SME rows: the rules of the evidence table are met, as in validation
        FieldRules rules = FieldRuleReader.read(mapping, file("none.mzTab", example.getBytes(StandardCharsets.UTF_8)));
        assertTrue(rules.parsed());
        assertEquals(Boolean.TRUE, byId(rules, "ms_level_must").present());
        assertEquals(Boolean.TRUE, byId(rules, "identification_method_must").present());

        assertEquals(Boolean.TRUE, byId(rules, "derivatized_form_may").present());

        // SME rows without a derivatized form: that rule is missing; the columns every row must have are present
        String header = "SEH\tSME_ID\tevidence_input_id\tdatabase_identifier\tchemical_formula\tsmiles\tinchi\t"
            + "chemical_name\turi\tderivatized_form\tadduct_ion\texp_mass_to_charge\tcharge\t"
            + "theoretical_mass_to_charge\tspectra_ref\tidentification_method\tms_level\t"
            + "id_confidence_measure[1]\trank\n";
        String row = "SME\t1\tinput\tnull\tnull\tnull\tnull\tPC 15:0\tnull\tnull\tnull\t100.0\t1\t100.0\t"
            + "ms_run[1]:scan=1\t[MS, MS:1001058, quality estimation by manual validation, ]\t"
            + "[MS, MS:1000511, ms level, 2]\t1.0\t1\n";
        FieldRules withRows = FieldRuleReader.read(mapping, file("rows.mzTab",
            (example + "\n" + header + row).getBytes(StandardCharsets.UTF_8)));
        assertTrue(withRows.parsed());
        assertEquals(Boolean.FALSE, byId(withRows, "derivatized_form_may").present());
        assertEquals(Boolean.TRUE, byId(withRows, "ms_level_must").present());
    }

    @Test
    void aSkeletonCannotBeReadIntoAModel() throws IOException {
        Assumptions.assumeTrue(servesTwoZero(), "the 2.0 skeleton");
        String skeleton = SkeletonWriter.write(new SkeletonRequest("2.0.0-M", null, "my-study"), Set.of("2.0.0-M"));
        FieldRules rules = FieldRuleReader.read(file("mapping.xml", resource(BUNDLED)),
            file("skeleton.mzTab", skeleton.getBytes(StandardCharsets.UTF_8)));
        assertFalse(rules.parsed());
        assertFalse(rules.rules().isEmpty());
        rules.rules().forEach(r -> assertNull(r.present()));
    }

    @Test
    void unparsedTextGivesUnknownPresence() throws IOException {
        String broken = "MTD\tmzTab-version\t" + engineVersion() + "\nMTD\tmzTab-ID\nMTD\tsoftware[1]\t[,,\n";
        FieldRules rules = FieldRuleReader.read(file("mapping.xml", resource(BUNDLED)),
            file("broken.mzTab", broken.getBytes(StandardCharsets.UTF_8)));
        assertFalse(rules.parsed());
        assertFalse(rules.rules().isEmpty());
        rules.rules().forEach(r -> assertNull(r.present()));
    }

    private static FieldRule byId(FieldRules rules, String id) {
        return rules.rules().stream().filter(r -> id.equals(r.id())).findFirst().orElseThrow();
    }

    private static FieldRule byPath(FieldRules rules, String path) {
        return rules.rules().stream().filter(r -> path.equals(r.path())).findFirst().orElseThrow();
    }

    private Path file(String name, byte[] content) throws IOException {
        return Files.write(dir.resolve(name), content);
    }

    private static byte[] resource(String path) throws IOException {
        try (InputStream in = FieldRuleReaderTest.class.getResourceAsStream(path)) {
            assertNotNull(in, path + " is missing from the test classpath");
            return in.readAllBytes();
        }
    }

    private static String engineVersion() throws IOException {
        java.util.Properties p = new java.util.Properties();
        try (InputStream in = FieldRuleReaderTest.class.getResourceAsStream("/application.properties")) {
            p.load(in);
        }
        return p.getProperty("engine.mztab-versions").split(",")[0].strip();
    }

    private static boolean servesTwoZero() throws IOException {
        return engineVersion().startsWith("2.0");
    }
}
