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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MzTabVersionDetectorTest {

    @TempDir
    Path dir;

    private static Path fixture(String name) throws IOException {
        try {
            return Path.of(MzTabVersionDetectorTest.class.getResource("/version-detection/" + name).toURI());
        } catch (URISyntaxException e) {
            throw new IOException(e);
        }
    }

    @Test
    void detectsTsvVersion() throws IOException {
        assertEquals(Optional.of("2.0.0-M"), MzTabVersionDetector.detect(fixture("tsv-first.mztab")));
    }

    @Test
    void detectsTsvVersionAfterBomWithCrlf() throws IOException {
        assertEquals(Optional.of("2.1.0-M"), MzTabVersionDetector.detect(fixture("tsv-bom-crlf.mztab")));
    }

    @Test
    void detectsLegacyTsvVersion() throws IOException {
        assertEquals(Optional.of("1.0.0"), MzTabVersionDetector.detect(fixture("tsv-legacy.mztab")));
    }

    @Test
    void missingVersionIsEmpty() throws IOException {
        assertEquals(Optional.empty(), MzTabVersionDetector.detect(fixture("tsv-missing.mztab")));
        assertEquals(Optional.empty(), MzTabVersionDetector.detect(fixture("json-missing.json")));
    }

    /** A comment line without a tab, as in jmzTab-m's example_study_variable_group.mztab. */
    @Test
    void bareCommentLinesBeforeTheVersionAreSkipped() throws IOException {
        Path f = Files.writeString(dir.resolve("bare-com.mztab"),
            "COM\tExample\nCOM\nCOM\r\nMTD\nCOM\tx\n\nMTD\tmzTab-version\t2.1.0-M\nMTD\tmzTab-ID\ta\n");
        assertEquals(Optional.of("2.1.0-M"), MzTabVersionDetector.detect(f));
    }

    @Test
    void readsOnlyUntilMetadataEnds() throws IOException {
        Path f = Files.writeString(dir.resolve("late.mztab"),
            "COM\tx\nMTD\tmzTab-ID\ta\nSMH\tx\nMTD\tmzTab-version\t2.0.0-M\n");
        assertEquals(Optional.empty(), MzTabVersionDetector.detect(f));
    }

    @Test
    void isJsonLooksAtTheFirstSignificantCharacter() throws IOException {
        assertTrue(MzTabVersionDetector.isJson(fixture("json-missing.json")));
        assertFalse(MzTabVersionDetector.isJson(fixture("tsv-first.mztab")));
        assertFalse(MzTabVersionDetector.isJson(fixture("tsv-bom-crlf.mztab")));
        assertTrue(MzTabVersionDetector.isJson(Files.writeString(dir.resolve("ws.json"), "﻿ \n {\"a\":1}")));
        assertFalse(MzTabVersionDetector.isJson(Files.write(dir.resolve("bin"), new byte[]{(byte) 0xff, (byte) 0xfe, 0x00})));
    }

    @Test
    void detectsVersionInJsonWhenMetadataComesFirst() throws IOException {
        assertEquals(Optional.of("2.1.0-M"), MzTabVersionDetector.detect(fixture("json-metadata-first.json")));
    }

    @Test
    void detectsVersionInJsonWhenMetadataComesLast() throws IOException {
        assertEquals(Optional.of("2.0.0-M"), MzTabVersionDetector.detect(fixture("json-metadata-last.json")));
    }

    @Test
    void detectsVersionInLargeJsonWithBoundedMemory() throws IOException {
        Path big = dir.resolve("big.json");
        try (OutputStream out = Files.newOutputStream(big);
            JsonGenerator g = new JsonFactory().createGenerator(out, JsonEncoding.UTF8)) {
            g.writeStartObject();
            g.writeArrayFieldStart("smallMoleculeSummary");
            for (int i = 0; i < 600_000; i++) {
                g.writeStartObject();
                g.writeNumberField("smlId", i);
                g.writeStringField("databaseIdentifier", "CHEBI:" + i + "-padding-padding-padding-padding-padding");
                g.writeStringField("chemicalName", "a lipid species with a rather long descriptive name");
                g.writeEndObject();
            }
            g.writeEndArray();
            g.writeObjectFieldStart("metadata");
            g.writeStringField("mzTab-version", "2.1.0-M");
            g.writeEndObject();
            g.writeEndObject();
        }
        assertTrue(Files.size(big) > 60_000_000L, "generated " + Files.size(big) + " bytes");
        assertTimeoutPreemptively(Duration.ofSeconds(10),
            () -> assertEquals(Optional.of("2.1.0-M"), MzTabVersionDetector.detect(big)));
    }

    /**
     * A file that is not UTF-8 throughout, here one ISO-8859-1 byte (µ, 0xB5)
     * after the version line: the version line is ASCII, so it is still found,
     * as the SPA's non-fatal TextDecoder and jmzTab-m (which replaces bad
     * bytes) read it. Without it the file would go to the default line.
     */
    @Test
    void detectsTsvVersionOfFileThatIsNotUtf8() throws IOException {
        assertEquals(Optional.of("2.1.0-M"), MzTabVersionDetector.detect(fixture("tsv-latin1.mztab")));
        assertEquals(Optional.of("2.1.0-M"), MzTabVersionDetector.detectTsv(fixture("tsv-latin1.mztab")));
        assertFalse(MzTabVersionDetector.isJson(fixture("tsv-latin1.mztab")));
    }

    @Test
    void isJsonOfFileStartingWithAnInvalidByteIsFalse() throws IOException {
        assertFalse(MzTabVersionDetector.isJson(Files.write(dir.resolve("b5"), new byte[]{(byte) 0xb5, '{'})));
        assertTrue(MzTabVersionDetector.isJson(Files.write(dir.resolve("b5-later"),
            new byte[]{' ', '{', '"', (byte) 0xb5, '"', ':', '1', '}'})));
    }

    /** Jackson coerces a scalar into the model's string field; routing must read what the engine will. */
    @Test
    void jsonVersionThatIsANumberIsReadAsItsText() throws IOException {
        assertEquals(Optional.of("2.1"), MzTabVersionDetector.detect(Files.writeString(dir.resolve("number.json"),
            "{\"metadata\":{\"mzTab-version\":2.1}}")));
        assertEquals(Optional.of("2.10"), MzTabVersionDetector.detect(Files.writeString(dir.resolve("n2.json"),
            "{\"metadata\":{\"mzTab-version\":2.10}}")));
        assertEquals(Optional.of("2"), MzTabVersionDetector.detect(Files.writeString(dir.resolve("int.json"),
            "{\"metadata\":{\"mzTab-version\":2}}")));
        assertEquals(Optional.of("true"), MzTabVersionDetector.detect(Files.writeString(dir.resolve("bool.json"),
            "{\"metadata\":{\"mzTab-version\":true}}")));
        assertEquals(Optional.empty(), MzTabVersionDetector.detect(Files.writeString(dir.resolve("null.json"),
            "{\"metadata\":{\"mzTab-version\":null}}")));
        assertEquals(Optional.empty(), MzTabVersionDetector.detect(Files.writeString(dir.resolve("obj.json"),
            "{\"metadata\":{\"mzTab-version\":{\"v\":\"2.1.0-M\"}}}")));
    }

    /** As Jackson binds it: of duplicate keys, the last one wins. */
    @Test
    void duplicateJsonKeysResolveLikeJacksonLastWins() throws IOException {
        assertEquals(Optional.of("2.1.0-M"), MzTabVersionDetector.detect(Files.writeString(dir.resolve("dv.json"),
            "{\"metadata\":{\"mzTab-version\":\"2.0.0-M\",\"mzTab-ID\":\"a\",\"mzTab-version\":\"2.1.0-M\"}}")));
        assertEquals(Optional.of("2.1.0-M"), MzTabVersionDetector.detect(Files.writeString(dir.resolve("dm.json"),
            "{\"metadata\":{\"mzTab-version\":\"2.0.0-M\"},\"smallMoleculeSummary\":[],"
            + "\"metadata\":{\"mzTab-version\":\"2.1.0-M\"}}")));
        // the later metadata object replaces the earlier one as a whole
        assertEquals(Optional.empty(), MzTabVersionDetector.detect(Files.writeString(dir.resolve("dm2.json"),
            "{\"metadata\":{\"mzTab-version\":\"2.1.0-M\"},\"metadata\":{\"mzTab-ID\":\"a\"}}")));
        assertEquals(Optional.empty(), MzTabVersionDetector.detect(Files.writeString(dir.resolve("dm3.json"),
            "{\"metadata\":{\"mzTab-version\":\"2.1.0-M\"},\"metadata\":null}")));
    }

    @Test
    void nestedMzTabVersionKeyOutsideMetadataIsIgnored() throws IOException {
        Path f = Files.writeString(dir.resolve("nested.json"),
            "{\"comment\":[{\"mzTab-version\":\"9.9\"}],\"metadata\":{\"mzTab-version\":\"2.0.0-M\"}}");
        assertEquals(Optional.of("2.0.0-M"), MzTabVersionDetector.detect(f));
    }
}
