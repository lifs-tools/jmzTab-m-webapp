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
package org.lifstools.mztab.validator.webapp.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class EnginesPropertiesTest {

    @Configuration
    @EnableConfigurationProperties(EnginesProperties.class)
    static class Config {
    }

    private static AnnotationConfigApplicationContext context(Map<String, Object> systemEnvironment,
        Map<String, Object> properties) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
        ctx.getEnvironment().getPropertySources().addLast(
            new SystemEnvironmentPropertySource("systemEnvironment", systemEnvironment));
        ctx.register(Config.class);
        return ctx;
    }

    @Test
    void bindsFromEnvironmentVariableNames() {
        try (AnnotationConfigApplicationContext ctx = context(Map.of(
            "ENGINES_DEFAULTLINE", "2.1",
            "ENGINES_MZTABM_0_LINE", "2.0", "ENGINES_MZTABM_0_URL", "http://a:8084",
            "ENGINES_MZTABM_1_LINE", "2.1", "ENGINES_MZTABM_1_URL", "https://b:8085",
            "ENGINES_UNAVAILABLEHINTURL", "https://example.org/hint"), Map.of())) {
            ctx.refresh();
            EnginesProperties p = ctx.getBean(EnginesProperties.class);
            assertEquals("2.1", p.defaultLine());
            assertEquals(2, p.mztabm().size());
            assertEquals(new EnginesProperties.Engine("2.0", URI.create("http://a:8084")), p.mztabm().get(0));
            assertEquals(new EnginesProperties.Engine("2.1", URI.create("https://b:8085")), p.mztabm().get(1));
            assertEquals("https://example.org/hint", p.unavailableHintUrl());
        }
    }

    @Test
    void bindsPropertyNamesAndAppliesDefaults() {
        try (AnnotationConfigApplicationContext ctx = context(Map.of(), Map.of(
            "engines.mztabm[0].line", "2.0", "engines.mztabm[0].url", "http://a:8084",
            "engines.connect-timeout", "2s"))) {
            ctx.refresh();
            EnginesProperties p = ctx.getBean(EnginesProperties.class);
            assertEquals("2.0", p.defaultLine());
            assertEquals(Duration.ofSeconds(2), p.connectTimeout());
            assertEquals(Duration.ofMinutes(10), p.jobTimeout());
        }
    }

    private static String failure(Map<String, Object> properties) {
        try (AnnotationConfigApplicationContext ctx = context(Map.of(), properties)) {
            try {
                ctx.refresh();
            } catch (RuntimeException e) {
                StringBuilder sb = new StringBuilder();
                for (Throwable t = e; t != null; t = t.getCause()) {
                    sb.append(t.getMessage()).append('\n');
                }
                return sb.toString();
            }
        }
        return null;
    }

    @Test
    void rejectsMalformedLine() {
        String message = failure(Map.of("engines.mztabm[0].line", "two", "engines.mztabm[0].url", "http://a:1"));
        assertNotNull(message);
        assertTrue(message.contains("line"), message);
    }

    @Test
    void rejectsNonHttpUrl() {
        String message = failure(Map.of("engines.mztabm[0].line", "2.0", "engines.mztabm[0].url", "ftp://a:1"));
        assertNotNull(message);
        assertTrue(message.contains("http"), message);
    }

    @Test
    void rejectsDuplicateLines() {
        String message = failure(Map.of("engines.mztabm[0].line", "2.0", "engines.mztabm[0].url", "http://a:1",
            "engines.mztabm[1].line", "2.0", "engines.mztabm[1].url", "http://b:1"));
        assertNotNull(message);
        assertTrue(message.contains("unique"), message);
    }
}
