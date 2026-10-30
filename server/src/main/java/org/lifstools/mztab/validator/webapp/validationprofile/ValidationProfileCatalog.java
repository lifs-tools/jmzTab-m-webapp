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
package org.lifstools.mztab.validator.webapp.validationprofile;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * The validation profiles shipped with the webapp. The catalog is read once
 * from an {@code index.json}; the mapping of a profile lives next to the index
 * at {@code <index directory>/{id}/{version}.xml}.
 */
@Component
public class ValidationProfileCatalog {

    private final ResourceLoader resourceLoader;
    private final String baseLocation;
    private final List<ValidationProfile> all;

    public ValidationProfileCatalog(ObjectMapper objectMapper, ResourceLoader resourceLoader,
        @Value("${validation.profiles.index:classpath:/validation-profiles/index.json}") String indexLocation) {
        this.resourceLoader = resourceLoader;
        this.baseLocation = indexLocation.substring(0, indexLocation.lastIndexOf('/') + 1);
        Resource index = resourceLoader.getResource(indexLocation);
        try (InputStream in = index.getInputStream()) {
            this.all = List.copyOf(objectMapper.readValue(in, new TypeReference<List<ValidationProfile>>() {
            }));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the validation profile index " + indexLocation, e);
        }
    }

    /** Every profile version in the index. */
    public List<ValidationProfile> all() {
        return all;
    }

    /** One entry per id: the highest version. */
    public List<ValidationProfile> latest() {
        Map<String, ValidationProfile> byId = new LinkedHashMap<>();
        for (ValidationProfile p : all) {
            byId.merge(p.id(), p, (a, b) -> compareVersions(a.version(), b.version()) >= 0 ? a : b);
        }
        return List.copyOf(byId.values());
    }

    /** The given version of a profile; a {@code null} version means the latest. */
    public Optional<ValidationProfile> find(String id, @Nullable String version) {
        if (version == null) {
            return all.stream().filter(p -> p.id().equals(id))
                .max(Comparator.comparing(ValidationProfile::version, ValidationProfileCatalog::compareVersions));
        }
        return all.stream().filter(p -> p.id().equals(id) && p.version().equals(version)).findFirst();
    }

    /**
     * The default rules of a version line: the latest version of the profile
     * {@code mztab-m-<line>-default}, e.g. {@code mztab-m-2.0-default}.
     *
     * @return empty if the catalog has no default profile for the line
     */
    public Optional<ValidationProfile> defaultFor(String line) {
        return find("mztab-m-" + line + "-default", null);
    }

    public URL mappingUrl(ValidationProfile p) {
        try {
            return resourceLoader.getResource(baseLocation + p.id() + "/" + p.version() + ".xml").getURL();
        } catch (IOException e) {
            throw new UncheckedIOException("Missing mapping for validation profile " + p.id() + " " + p.version(), e);
        }
    }

    /** Numeric comparison per dot-separated segment, so 1.10.0 is higher than 1.9.0. */
    static int compareVersions(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int c = Integer.compare(i < x.length ? Integer.parseInt(x[i]) : 0,
                i < y.length ? Integer.parseInt(y[i]) : 0);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }
}
