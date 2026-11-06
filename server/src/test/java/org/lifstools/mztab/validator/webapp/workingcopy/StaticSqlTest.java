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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Guards the SQL rules: every statement run through a JDBC template is a
 * {@code static final String} constant with named parameters, no {@code ?},
 * no concatenation. The scan covers the {@code workingcopy} package and all its
 * subpackages, so later additions are checked without changing this test.
 */
class StaticSqlTest {

    private static final Path ROOT = Paths.get(System.getProperty("basedir", System.getProperty("user.dir")),
        "src/main/java/org/lifstools/mztab/validator/webapp/workingcopy");

    /** receiver names containing jdbc/template, a template method, and the first argument */
    private static final Pattern CALL = Pattern.compile(
        "(?i)\\b\\w*(?:jdbc|template)\\w*\\s*\\.\\s*(query|queryForObject|queryForList|queryForMap|queryForStream"
        + "|queryForRowSet|update|batchUpdate|execute)\\s*\\(\\s*([^,)]*?)\\s*[,)]");

    @Test
    void sqlIsStaticWithNamedParameters() throws IOException {
        List<Path> files;
        try (Stream<Path> s = Files.walk(ROOT)) {
            files = s.filter(p -> p.toString().endsWith(".java")).toList();
        }
        assertFalse(files.isEmpty(), "no sources found under " + ROOT.toAbsolutePath());
        int calls = 0;
        List<String> problems = new ArrayList<>();
        for (Path file : files) {
            String src = Files.readString(file);
            Matcher m = CALL.matcher(src);
            while (m.find()) {
                calls++;
                String arg = m.group(2);
                if (!arg.matches("[A-Z][A-Z0-9_]*")) {
                    problems.add(file + ": SQL argument is not a constant: " + arg);
                    continue;
                }
                Matcher c = Pattern.compile("static\\s+final\\s+String\\s+" + arg
                    + "\\s*=\\s*(\"\"\"[\\s\\S]*?\"\"\"|\"(?:[^\"\\\\]|\\\\.)*\")\\s*;").matcher(src);
                if (!c.find()) {
                    problems.add(file + ": " + arg + " is not a static final String literal (no concatenation)");
                    continue;
                }
                String unquoted = c.group(1).replaceAll("'(?:[^']|'')*'", "");
                if (unquoted.contains("?")) {
                    problems.add(file + ": " + arg + " contains a '?' placeholder");
                }
            }
        }
        assertTrue(calls > 0, "the scan found no JDBC calls; the pattern is stale");
        assertEquals(List.of(), problems);
    }
}
