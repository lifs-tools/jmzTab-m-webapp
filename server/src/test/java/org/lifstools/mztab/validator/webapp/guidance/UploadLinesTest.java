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
package org.lifstools.mztab.validator.webapp.guidance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.lifstools.mztab.validator.webapp.service.StorageService;
import org.lifstools.mztab.validator.webapp.service.StorageService.SLOT;

class UploadLinesTest {

    private final UUID session = UUID.randomUUID();

    private UploadLines with(String content) {
        StorageService storage = mock(StorageService.class);
        when(storage.read(eq(session), eq(SLOT.MZTABFILE), any())).thenAnswer(
            i -> Optional.of(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))));
        return new UploadLines(storage);
    }

    @Test
    void readsRequestedLinesOnly() {
        String file = IntStream.rangeClosed(1, 10).mapToObj(i -> "line " + i).collect(Collectors.joining("\n"));
        Map<Long, String> lines = with(file).lines(session, Set.of(2L, 9L, 42L));
        assertEquals(Map.of(2L, "line 2", 9L, "line 9"), lines);
        assertFalse(lines.containsKey(42L));
    }

    @Test
    void softwareNamesFromParam() {
        String file = "MTD\tmzTab-version\t2.0.0-M\n"
            + "MTD\tsoftware[1]\t[MS, MS:1001456, LipidCompass curation script, v0.0.7]\n"
            + "MTD\tsoftware[1]-setting[1]\tx\n";
        assertEquals(List.of("LipidCompass curation script"), with(file).softwareNames(session));
    }

    @Test
    void missingUploadGivesNothing() {
        StorageService storage = mock(StorageService.class);
        when(storage.read(eq(session), eq(SLOT.MZTABFILE), any())).thenReturn(Optional.empty());
        UploadLines lines = new UploadLines(storage);
        assertEquals(Map.of(), lines.lines(session, Set.of(1L)));
        assertEquals(List.of(), lines.softwareNames(session));
    }
}
