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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lifstools.mztab2.io.MzTabFileParser;
import org.lifstools.mztab2.model.MzTab;
import uk.ac.ebi.pride.jmztab2.utils.errors.MZTabErrorType;

class SectionRendererTest {

    private static final String NO_SMF_SME =
        "/metabolomics/publication-samples/lipidomics-example-missing-feature-and-evidence.mzTab";

    @TempDir
    Path dir;

    @Test
    void rendersMetadataAsPrefixKeyValue() throws IOException {
        SectionRenderer.Rendered rendered = SectionRenderer.render(parse(Fixtures.LIPIDOMICS));

        assertThat(rendered.metadata()).isNotEmpty().allSatisfy(cells -> {
            assertThat(cells).hasSizeGreaterThanOrEqualTo(3);
            assertThat(cells.get(0)).isEqualTo("MTD");
        });
        assertThat(rendered.metadata().get(0).get(1)).isEqualTo("mzTab-version");
    }

    @Test
    void rendersTablesWithMzTabColumnNamesAndPrefixes() throws IOException {
        SectionRenderer.Rendered rendered = SectionRenderer.render(parse(Fixtures.LIPIDOMICS));

        List<List<String>> sml = rendered.tables().get(SectionRenderer.Table.SML);
        assertThat(sml.get(0).get(0)).isEqualTo("SMH");
        assertThat(sml.get(0).get(1)).isEqualTo("SML_ID");
        assertThat(sml.get(0)).contains("abundance_assay[1]");
        assertThat(sml.subList(1, sml.size())).isNotEmpty().allSatisfy(row -> {
            assertThat(row.get(0)).isEqualTo("SML");
            assertThat(row).hasSameSizeAs(sml.get(0));
        });
        assertThat(rendered.tables()).containsKeys(SectionRenderer.Table.SMF, SectionRenderer.Table.SME);
    }

    @Test
    void absentTablesAreNotRendered() throws IOException {
        SectionRenderer.Rendered rendered = SectionRenderer.render(parse(NO_SMF_SME));

        assertThat(rendered.tables()).containsOnlyKeys(SectionRenderer.Table.SML);
    }

    @Test
    void doesNotStampVersionIntoFileWithoutOne() throws IOException {
        MzTab mzTab = parse(Fixtures.MINIMAL);
        mzTab.getMetadata().setMzTabVersion(null);

        SectionRenderer.Rendered rendered = SectionRenderer.render(mzTab);

        assertThat(rendered.metadata()).isNotEmpty()
            .noneSatisfy(cells -> assertThat(cells.get(1)).isEqualTo("mzTab-version"));
        assertThat(mzTab.getMetadata().getMzTabVersion()).as("the model is not changed").isNull();
    }

    private MzTab parse(String resource) throws IOException {
        Path file = Files.write(dir.resolve("file.mztab"), Fixtures.ofThisLine(resource));
        MzTabFileParser parser = new MzTabFileParser(file.toFile());
        parser.parse(System.out, MZTabErrorType.Level.Error, 100);
        assertThat(parser.getMZTabFile()).as("errors: %s", parser.getErrorList()).isNotNull();
        return parser.getMZTabFile();
    }
}
