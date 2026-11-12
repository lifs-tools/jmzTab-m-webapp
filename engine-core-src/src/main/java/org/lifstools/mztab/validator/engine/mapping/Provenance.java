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
package org.lifstools.mztab.validator.engine.mapping;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.lifstools.mztab.submission.Vocabulary.Param;
import org.lifstools.mztab.submission.mapping.Mapping;
import org.lifstools.mztab.submission.mapping.Render;

/**
 * The webapp as a {@code software[k]} entry of the file it helped to write (design §5.3): the parameter
 * {@code [, , jmzTab-m-webapp, <version>]}, with its settings: {@code vocabulary <version>}, the output mapping
 * applied ({@code <id> <version>}) and, when known, the input mapping that produced the instance. When the copy already lists the webapp with this version, that entry is used again, else {@code k}
 * is the next free software index.
 */
final class Provenance {

    /** The name under which the webapp lists itself. */
    static final String NAME = "jmzTab-m-webapp";

    private static final Pattern SOFTWARE = Pattern.compile("^software\\[(\\d{1,9})\\]");
    private static final String BUILD_INFO = "META-INF/build-info.properties";
    private static volatile String webappVersion;

    /**
     * @param index the software index
     * @param existing whether the copy lists the webapp at that index already
     * @param lines the software line and its settings
     */
    record Entry(int index, boolean existing, List<MappingApplier.KeyValue> lines) {
    }

    private Provenance() {
    }

    /**
     * The webapp's entry.
     *
     * @param copy the copy
     * @param written the metadata lines written so far, whose {@code software[n]} indexes are taken
     * @param vocabularyVersion the version of the vocabulary the mapping was applied with
     * @param mapping the output mapping
     * @param inputMapping the input mapping as {@code "<id> <version>"}, or null to leave setting 3 out
     */
    static Entry of(CopyIndex copy, List<MappingApplier.KeyValue> written, String vocabularyVersion, Mapping mapping,
            String inputMapping) {
        String software = Render.param(new Param("", "", NAME, webappVersion()));
        int index = copy.indexOf("software", software);
        boolean existing = index > 0;
        if (!existing) {
            index = copy.next("software");
            for (MappingApplier.KeyValue kv : written) {
                Matcher m = SOFTWARE.matcher(kv.key());
                if (m.find()) {
                    index = Math.max(index, Integer.parseInt(m.group(1)) + 1);
                }
            }
        }
        String k = "software[" + index + "]";
        List<MappingApplier.KeyValue> lines = new ArrayList<>(List.of(new MappingApplier.KeyValue(k, software),
                new MappingApplier.KeyValue(k + "-setting[1]", "vocabulary " + vocabularyVersion),
                new MappingApplier.KeyValue(k + "-setting[2]", mapping.id() + " " + mapping.version())));
        if (inputMapping != null) {
            lines.add(new MappingApplier.KeyValue(k + "-setting[3]", inputMapping));
        }
        return new Entry(index, existing, List.copyOf(lines));
    }

    /**
     * The webapp's version: {@code build.version} of the engine's {@code META-INF/build-info.properties} (written by
     * the Spring Boot build-info goal; engines and webapp share the reactor version), or {@code unknown}.
     */
    static String webappVersion() {
        String v = webappVersion;
        if (v == null) {
            Properties p = new Properties();
            try (InputStream in = Provenance.class.getClassLoader().getResourceAsStream(BUILD_INFO)) {
                if (in != null) {
                    p.load(in);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            v = p.getProperty("build.version", "unknown");
            webappVersion = v;
        }
        return v;
    }
}
