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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DocsPropertiesTest {

    @Test
    void blankIsNull() {
        DocsProperties p = new DocsProperties("  ", "");
        assertNull(p.submissionTemplateUrl());
        assertNull(p.submissionTemplateExampleUrl());
        assertNull(new DocsProperties(null, null).submissionTemplateUrl());
    }

    @Test
    void httpsIsKept() {
        DocsProperties p = new DocsProperties("https://x.org/t.xlsx", " https://x.org/e.xlsx ");
        assertEquals("https://x.org/t.xlsx", p.submissionTemplateUrl());
        assertEquals("https://x.org/e.xlsx", p.submissionTemplateExampleUrl());
    }

    @Test
    void httpIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> new DocsProperties("http://x", null));
        assertTrue(e.getMessage().contains("docs.submission-template-url"), e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> new DocsProperties(null, "http://x"));
        assertTrue(e.getMessage().contains("docs.submission-template-example-url"), e.getMessage());
    }

    @Test
    void relativeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new DocsProperties("/assets/t.xlsx", null));
        assertThrows(IllegalArgumentException.class, () -> new DocsProperties("not a uri", null));
    }

    @Test
    void tooLongIsRejected() {
        String ok = "https://x.org/" + "a".repeat(2048 - "https://x.org/".length());
        assertEquals(2048, ok.length());
        assertEquals(ok, new DocsProperties(ok, null).submissionTemplateUrl());
        assertThrows(IllegalArgumentException.class, () -> new DocsProperties(ok + "a", null));
    }
}
