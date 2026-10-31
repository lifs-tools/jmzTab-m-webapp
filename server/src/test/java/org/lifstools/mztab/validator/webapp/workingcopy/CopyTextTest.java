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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CopyTextTest {

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static CopyText.Parsed read(byte[] b, SourceEncoding enc) throws IOException {
        return CopyText.read(new ByteArrayInputStream(b), enc);
    }

    private static byte[] write(CopyText.Parsed p) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CopyText.write(p.bom(), p.lines().iterator(), out);
        return out.toByteArray();
    }

    @Test
    void roundTripsMixedEndingsAndBom() throws IOException {
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = bytes("MTD\ta\tb\r\nMTD\tc\td\nSMH\tx\rSML\t1");
        byte[] all = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, all, 0, 3);
        System.arraycopy(body, 0, all, 3, body.length);
        CopyText.Parsed p = read(all, SourceEncoding.UTF_8);
        assertTrue(p.bom());
        assertEquals(4, p.lines().size());
        assertEquals("\r\n", p.lines().get(0).eol());
        assertEquals("\n", p.lines().get(1).eol());
        assertEquals("\r", p.lines().get(2).eol());
        assertEquals("", p.lines().get(3).eol());
        assertArrayEquals(all, write(p));
    }

    @Test
    void keepsEmptyTrailingCells() throws IOException {
        CopyText.Parsed p = read(bytes("SML\t1\t\t\n"), SourceEncoding.UTF_8);
        assertEquals(java.util.List.of("SML", "1", "", ""), p.lines().get(0).cells());
        assertEquals("SML\t1\t\t", p.lines().get(0).text());
    }

    @Test
    void sectionsAndUnknownPrefix() throws IOException {
        CopyText.Parsed p = read(bytes("mtd\tx\ty\n\nCOM\tnote\n"), SourceEncoding.UTF_8);
        assertEquals("?", p.lines().get(0).section());
        assertEquals("BLANK", p.lines().get(1).section());
        assertEquals("COM", p.lines().get(2).section());
        assertEquals(3, p.lines().get(2).number());
    }

    @Test
    void refusesInvalidUtf8WithLine() {
        byte[] b = {'a', '\n', 'b', '\n', 'c', (byte) 0xE9, '\n'};
        NotUtf8Exception e = assertThrows(NotUtf8Exception.class, () -> read(b, SourceEncoding.UTF_8));
        assertEquals(3, e.lineNumber());
        assertEquals("The file is not encoded as UTF-8; the first line that is not is line 3.", e.getMessage());
    }

    @Test
    void reencodesLatin1() throws IOException {
        byte[] b = {'a', '\n', 'b', '\n', 'c', (byte) 0xE9, '\n'};
        CopyText.Parsed p = read(b, SourceEncoding.WINDOWS_1252);
        assertTrue(p.lines().get(2).text().contains("é"));
        assertEquals("cé\n", new String(write(p), StandardCharsets.UTF_8).substring(4));
    }

    @Test
    void bomCharacterAfterLineOneIsContent() throws IOException {
        for (String in : new String[] {"a\n\uFEFFb", "a\n\uFEFFb\n"}) {
            byte[] b = bytes(in);
            CopyText.Parsed p = read(b, SourceEncoding.UTF_8);
            assertFalse(p.bom());
            assertArrayEquals(b, write(p));
        }
    }
}
