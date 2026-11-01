/*
 * Copyright 2018 Leibniz Institut für Analytische Wissenschaften - ISAS e.V..
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

import org.lifstools.mztab.validator.webapp.domain.ExampleFile;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/**
 * The examples of {@code examples.properties}, prefix {@code example}. Bound by {@link #bind}, not as
 * {@code @ConfigurationProperties}: see there.
 *
 * @author nilshoffmann
 */
public class ExampleFileConfig {

    public static final String PREFIX = "example";

    /**
     * Binds the examples with the shared conversion service only, so every URI is bound as written. Bound as
     * {@code @ConfigurationProperties}, the application context's {@code URIEditor} would resolve a {@code classpath:}
     * URI of an existing resource to its {@code file:} or {@code jar:} URL, and a bundled example, which is
     * recognised by its {@code classpath:tutorial/<name>} URI, would no longer be one.
     */
    public static ExampleFileConfig bind(Environment environment) {
        return Binder.get(environment).bind(PREFIX, ExampleFileConfig.class).orElseGet(ExampleFileConfig::new);
    }

    private List<ExampleFile> exampleFile = new ArrayList<>();

    public List<ExampleFile> getExampleFile() {
        return exampleFile;
    }

    public void setExampleFile(List<ExampleFile> exampleFile) {
        this.exampleFile = exampleFile;
    }

}
