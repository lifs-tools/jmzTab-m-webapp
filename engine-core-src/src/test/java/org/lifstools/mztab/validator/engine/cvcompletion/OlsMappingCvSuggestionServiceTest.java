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
package org.lifstools.mztab.validator.engine.cvcompletion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.lifstools.mztab2.cvmapping.CvParameterLookupService;
import uk.ac.ebi.pride.utilities.ols.web.service.client.OLSClient;
import uk.ac.ebi.pride.utilities.ols.web.service.model.Ontology;

/** The CV suggestions do no network I/O until they are used: an engine starts offline. */
class OlsMappingCvSuggestionServiceTest {

    private final OLSClient client = mock(OLSClient.class);

    @Test
    void constructionDoesNotAskTheOntologyLookupService() throws Exception {
        OlsMappingCvSuggestionService service = new OlsMappingCvSuggestionService(client,
            mock(CvParameterLookupService.class));

        assertThat(service.getRules()).isNotEmpty();
        assertThat(service.getCvReferences()).isNotEmpty();
        verifyNoInteractions(client);
    }

    @Test
    void anOntologyIsAskedForOnceOnFirstUse() throws Exception {
        Ontology ms = mock(Ontology.class);
        when(client.getOntology("ms")).thenReturn(ms);
        OlsMappingCvSuggestionService service = new OlsMappingCvSuggestionService(client,
            mock(CvParameterLookupService.class));

        assertThat(service.resolveCv("MS")).containsSame(ms);
        assertThat(service.resolveCv("ms")).containsSame(ms);
        verify(client, times(1)).getOntology("ms");
    }

    @Test
    void anUnknownOntologyIsEmptyAndAskedForAgain() throws Exception {
        OlsMappingCvSuggestionService service = new OlsMappingCvSuggestionService(client,
            mock(CvParameterLookupService.class));

        assertThat(service.resolveCv("NOPE")).isEmpty();
        assertThat(service.resolveCv("NOPE")).isEmpty();
        verify(client, times(2)).getOntology("nope");
    }
}
