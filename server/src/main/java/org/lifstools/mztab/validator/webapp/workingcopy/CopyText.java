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

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Reads and writes the exact text of a file as lines of cells. Only text is
 * handled here (line endings, cells, section prefix); the content is never
 * interpreted as mzTab-M. Writing what was read yields the same bytes for every
 * valid UTF-8 input.
 */
public final class CopyText {

    private static final Set<String> SECTIONS = Set.of("MTD", "SMH", "SML", "SFH", "SMF", "SEH", "SME", "COM");
    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    /** The parsed file. */
    public record Parsed(boolean bom, List<Line> lines) {
    }

    private CopyText() {
    }

    /**
     * Builds a line from its text (without ending). A section is derived from
     * the first cell.
     */
    public static Line line(long number, String text, String eol) {
        List<String> cells = Arrays.asList(text.split("\t", -1));
        String first = cells.get(0);
        String section = text.isEmpty() ? "BLANK" : SECTIONS.contains(first) ? first : "?";
        return new Line(number, section, cells, eol);
    }

    /**
     * Splits the input on CRLF, LF and CR, keeping each ending. Only a leading
     * UTF-8 byte order mark is stripped (and remembered), and only for UTF-8.
     *
     * @throws NotUtf8Exception for {@link SourceEncoding#UTF_8} when a line is
     * not valid UTF-8
     */
    public static Parsed read(InputStream in, SourceEncoding enc) throws IOException {
        Charset charset = switch (enc) {
            case UTF_8 -> StandardCharsets.UTF_8;
            case WINDOWS_1252 -> Charset.forName("windows-1252");
            case ISO_8859_1 -> StandardCharsets.ISO_8859_1;
        };
        // CR and LF never occur inside a multi-byte UTF-8 sequence, so lines can
        // be cut on the bytes and decoded one by one (which gives the bad line).
        BufferedInputStream bin = new BufferedInputStream(in, 1 << 16);
        List<Line> lines = new ArrayList<>();
        ByteArrayOutputStream cur = new ByteArrayOutputStream();
        boolean bom = false;
        boolean first = true;
        int c;
        long n = 0;
        while ((c = bin.read()) != -1) {
            if (c == '\n' || c == '\r') {
                String eol = "\n";
                if (c == '\r') {
                    bin.mark(1);
                    int next = bin.read();
                    if (next == '\n') {
                        eol = "\r\n";
                    } else {
                        eol = "\r";
                        bin.reset();
                    }
                }
                byte[] raw = cur.toByteArray();
                cur.reset();
                if (first) {
                    first = false;
                    if (enc == SourceEncoding.UTF_8 && startsWithBom(raw)) {
                        bom = true;
                        raw = Arrays.copyOfRange(raw, 3, raw.length);
                    }
                }
                lines.add(line(++n, decode(raw, charset, enc, n), eol));
            } else {
                cur.write(c);
            }
        }
        if (cur.size() > 0 || first) {
            byte[] raw = cur.toByteArray();
            if (enc == SourceEncoding.UTF_8 && startsWithBom(raw)) {
                bom = true;
                raw = Arrays.copyOfRange(raw, 3, raw.length);
            }
            // an empty input has no lines; a BOM alone gives no line either
            if (raw.length > 0) {
                lines.add(line(++n, decode(raw, charset, enc, n), ""));
            }
        }
        return new Parsed(bom, lines);
    }

    private static boolean startsWithBom(byte[] raw) {
        return raw.length >= 3 && raw[0] == BOM[0] && raw[1] == BOM[1] && raw[2] == BOM[2];
    }

    private static String decode(byte[] raw, Charset charset, SourceEncoding enc, long lineNumber) {
        if (enc != SourceEncoding.UTF_8) {
            return new String(raw, charset);
        }
        try {
            return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();
        } catch (CharacterCodingException e) {
            throw new NotUtf8Exception(lineNumber);
        }
    }

    /** Writes the lines as UTF-8, with the byte order mark if {@code bom}. */
    public static void write(boolean bom, Iterator<Line> lines, OutputStream out) throws IOException {
        if (bom) {
            out.write(BOM);
        }
        while (lines.hasNext()) {
            Line l = lines.next();
            out.write((l.text() + l.eol()).getBytes(StandardCharsets.UTF_8));
        }
        out.flush();
    }
}
