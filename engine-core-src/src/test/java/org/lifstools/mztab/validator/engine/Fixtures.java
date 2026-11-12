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
package org.lifstools.mztab.validator.engine;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fixtures of the shared suite. They come from the engine's own
 * jmztabm-test-utils, whose files declare {@code 2.0.0-M}; the suite runs
 * against every engine, so it rewrites the declared version where the line
 * matters.
 */
final class Fixtures {

    static final String LIPIDOMICS = "/metabolomics/lipidomics-example.mzTab";
    static final String MINIMAL = "/metabolomics/minimal-m-2.0.mztab";
    /** The CV mapping bundled with the engine's jmzTab-m; the catalog default of its line is a copy. */
    static final String BUNDLED_MAPPING = "/mappings/mzTab-M-mapping.xml";

    private static final Pattern VERSION_LINE = Pattern.compile("(?m)^MTD\\tmzTab-version\\t[^\\t\\r\\n]*");

    private Fixtures() {
    }

    /** The first declared version this engine accepts, from its filtered {@code application.properties}. */
    static String engineVersion() {
        Properties properties = new Properties();
        try (InputStream in = Fixtures.class.getResourceAsStream("/application.properties")) {
            properties.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return properties.getProperty("engine.mztab-versions").split(",")[0].strip();
    }

    /** A fixture rewritten to declare this engine's first accepted version. */
    static byte[] ofThisLine(String resource) {
        return withVersion(resource(resource), engineVersion());
    }

    static byte[] resource(String path) {
        try (InputStream in = Fixtures.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException(path + " is missing from the test classpath");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The file with its {@code mzTab-version} value replaced. */
    static byte[] withVersion(byte[] tsv, String version) {
        String text = new String(tsv, StandardCharsets.UTF_8);
        Matcher m = VERSION_LINE.matcher(text);
        if (!m.find()) {
            throw new IllegalArgumentException("The fixture declares no mzTab-version");
        }
        return m.replaceFirst(Matcher.quoteReplacement("MTD\tmzTab-version\t" + version))
            .getBytes(StandardCharsets.UTF_8);
    }

    /** The file without its {@code mzTab-version} line. */
    static byte[] withoutVersion(byte[] tsv) {
        String text = new String(tsv, StandardCharsets.UTF_8);
        return text.replaceFirst("(?m)^MTD\\tmzTab-version\\t[^\\n]*\\n", "").getBytes(StandardCharsets.UTF_8);
    }

    /** A version of the other line (ruling R3): the 2.0 engine gets 2.1, every other engine 2.0. */
    static String otherLineVersion(String line) {
        return "2.0".equals(line) ? "2.1.0-M" : "2.0.0-M";
    }
}
