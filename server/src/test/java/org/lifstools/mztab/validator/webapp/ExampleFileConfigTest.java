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
package org.lifstools.mztab.validator.webapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.lifstools.mztab.validator.webapp.domain.ExampleFile;
import org.springframework.beans.support.ResourceEditorRegistrar;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * A bundled example is recognised by its {@code classpath:tutorial/<name>} URI, so the example URIs must be bound as
 * written. An application context's property editors would resolve a {@code classpath:} URI of an existing resource
 * to its file or JAR URL.
 */
class ExampleFileConfigTest {

    /** A class path resource that exists, as a bundled example's file does in the application. */
    static final String EXISTING = "classpath:tutorial-fixture/fixture-2.0.mztab";

    static final Map<String, Object> PROPERTIES = Map.of(
        "example.exampleFile[0].name", "fixture-2.0.mztab",
        "example.exampleFile[0].uri", EXISTING,
        "example.exampleFile[0].tour", "classpath:tutorial-fixture/fixture-2.0.tour.json",
        "example.exampleFile[0].originalUri", "https://example.org/original.mztab",
        "example.exampleFile[1].name", "remote.mztab",
        "example.exampleFile[1].uri", "https://raw.githubusercontent.com/HUPO-PSI/mzTab/master/x.mztab");

    @Test
    void bindsUrisAsWritten() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("examples", PROPERTIES));

        List<ExampleFile> files = ExampleFileConfig.bind(environment).getExampleFile();

        assertEquals(URI.create(EXISTING), files.get(0).getUri());
        assertEquals(URI.create("classpath:tutorial-fixture/fixture-2.0.tour.json"), files.get(0).getTour());
        assertEquals(URI.create("https://example.org/original.mztab"), files.get(0).getOriginalUri());
        assertEquals(URI.create("https://raw.githubusercontent.com/HUPO-PSI/mzTab/master/x.mztab"),
            files.get(1).getUri());
    }

    @Test
    void bindsNothingWithoutExamples() {
        assertTrue(ExampleFileConfig.bind(new StandardEnvironment()).getExampleFile().isEmpty());
    }

    /** Why {@link ExampleFileConfig#bind} exists: the context's editors turn the URI into the resource's URL. */
    @Test
    void contextEditorsWouldResolveClasspathUris() {
        ExampleFileConfig bound = new Binder(List.of(new MapConfigurationPropertySource(PROPERTIES)), null, null,
            editors -> new ResourceEditorRegistrar(new DefaultResourceLoader(), new StandardEnvironment())
                .registerCustomEditors(editors)).bind("example", ExampleFileConfig.class).get();

        assertNotEquals("classpath", bound.getExampleFile().get(0).getUri().getScheme());
    }
}
