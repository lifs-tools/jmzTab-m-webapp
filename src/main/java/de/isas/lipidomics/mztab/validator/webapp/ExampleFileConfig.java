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
package de.isas.lipidomics.mztab.validator.webapp;

import de.isas.lipidomics.mztab.validator.webapp.domain.ExampleFile;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

/**
 *
 * @author nilshoffmann
 */
@Configuration
@PropertySource(name="props", value="classpath:examples.properties", ignoreResourceNotFound = false)
@ConfigurationProperties(prefix = "example")
public class ExampleFileConfig {
    private List<ExampleFile> exampleFile = new ArrayList<>();

    public List<ExampleFile> getExampleFile() {
        return exampleFile;
    }

    public void setExampleFile(List<ExampleFile> exampleFile) {
        this.exampleFile = exampleFile;
    }

}
