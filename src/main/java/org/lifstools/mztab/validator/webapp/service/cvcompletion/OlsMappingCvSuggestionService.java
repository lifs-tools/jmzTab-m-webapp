/*
 * Copyright 2019 Leibniz Institut für Analytische Wissenschaften - ISAS e.V..
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
package org.lifstools.mztab.validator.webapp.service.cvcompletion;

import org.lifstools.mztab2.cvmapping.CvMappingUtils;
import org.lifstools.mztab2.cvmapping.CvParameterLookupService;
import org.lifstools.mztab2.cvmapping.ParameterComparisonResult;
import static org.lifstools.mztab2.cvmapping.ParameterComparisonResult.CHILD_OF;
import static org.lifstools.mztab2.cvmapping.ParameterComparisonResult.IDENTICAL;
import org.lifstools.mztab2.model.Parameter;
import info.psidev.cvmapping.CvMapping;
import info.psidev.cvmapping.CvMappingRule;
import info.psidev.cvmapping.CvReference;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import uk.ac.ebi.pride.utilities.ols.web.service.client.OLSClient;
import uk.ac.ebi.pride.utilities.ols.web.service.model.Identifier;
import uk.ac.ebi.pride.utilities.ols.web.service.model.Ontology;
import uk.ac.ebi.pride.utilities.ols.web.service.model.Term;

/**
 *
 * @author nils.hoffmann
 */
@Slf4j
@Service
public class OlsMappingCvSuggestionService {

    private final OLSClient client;
    private final CvParameterLookupService lookupService;
    private final CvMapping cvMapping;
    private final Map<String, CvMappingRule> ruleIdLookup = new ConcurrentHashMap<>();
    private final Map<String, CvMappingRule> selectorRuleLookup = new ConcurrentHashMap<>();
    private final Map<String, Ontology> ontologyLookup = new ConcurrentHashMap<>();

    @Autowired
    public OlsMappingCvSuggestionService(OLSClient client, CvParameterLookupService lookupService) throws JAXBException, IOException {
        this.client = client;
        JAXBContext jaxbContext = JAXBContext.newInstance(CvMapping.class);
        Unmarshaller u = jaxbContext.createUnmarshaller();
        Resource res = new DefaultResourceLoader().getResource("mappings/mzTab-M-mapping.xml");
        this.cvMapping = (CvMapping) u.unmarshal(res.getInputStream());
        this.cvMapping.getCvMappingRuleList().getCvMappingRule().stream().forEach((cvMappingRule) -> {
            ruleIdLookup.put(cvMappingRule.getId(), cvMappingRule);
            if (selectorRuleLookup.containsKey(cvMappingRule.getCvElementPath())) {
                throw new IllegalArgumentException("cvElementPath " + cvMappingRule.getCvElementPath() + " in mapping file " + res.getFilename() + " occurs more than once! Must be unique!");
            }
            selectorRuleLookup.put(cvMappingRule.getCvElementPath(), cvMappingRule);
        });
        this.cvMapping.getCvReferenceList().getCvReference().stream().forEach((cvRef) -> {
            Optional<Ontology> optOntology = resolveCv(cvRef.getCvIdentifier());
            if (optOntology.isPresent()) {
                ontologyLookup.put(cvRef.getCvIdentifier().toLowerCase(), optOntology.get());
            }
        });
        this.lookupService = lookupService;
    }

    public Optional<CvMappingRule> getRuleForProperty(String propertyPath) {
        // property path: "metadata.msRun.format"
        LinkedList<String> segments = new LinkedList<>(Arrays.asList(propertyPath.split("\\.")));
        if (segments.size() == 1) {
            log.warn("Could not find '.' in property path: {}", propertyPath);
        }
        String propertySuffix = segments.removeLast();

        String matchingSelector = "/" + segments.stream().collect(Collectors.joining("/")) + "/@" + propertySuffix;
        log.debug("Looking for rule with selector {}", matchingSelector);
        return Optional.ofNullable(selectorRuleLookup.get(matchingSelector));
        // rule selector: "/metadata/msRun/@format"
    }

    public List<CvMappingRule> getRules() {
        return this.cvMapping.getCvMappingRuleList().getCvMappingRule();
    }

    public List<CvReference> getCvReferences() {
        return this.cvMapping.getCvReferenceList().getCvReference();
    }

    public Optional<Ontology> resolveCv(String cvName) {
        if(ontologyLookup.containsKey(cvName.toLowerCase())) {
            return Optional.of(ontologyLookup.get(cvName.toLowerCase()));
        } else {
            Ontology onto = client.getOntology(cvName.toLowerCase());
            ontologyLookup.put(cvName.toLowerCase(), onto);
            return Optional.of(ontologyLookup.get(cvName.toLowerCase()));
        }
    }
    
    public List<Term> suggestParameters(String partialParamName, CvMappingRule rule, int levels) {
        return rule.getCvTerm().stream().map((cvTerm)
                    -> {
                CvReference ref = cvTerm.getCvIdentifierRef();
                String cvIdentifier = ref.getCvIdentifier();
                Identifier ident = new Identifier(cvTerm.getTermAccession(), Identifier.IdentifierType.OBO);
                Term termIri = client.retrieveTerm(cvTerm.getTermAccession(), cvIdentifier.toLowerCase());
                return client.getTermsByNameFromParent(partialParamName.replaceAll("[-_:]+", " ") + "*", cvIdentifier.toLowerCase(), false, termIri.getIri().getIdentifier()).stream().filter((term) -> {
                    Parameter parentTerm = CvMappingUtils.asParameter(cvTerm);
                    log.debug("Parent term: {}", parentTerm);
                    Parameter childTerm = CvMappingUtils.asParameter(term);
                    childTerm.setCvLabel(term.getOntologyName().toUpperCase());
                    log.debug("Child term: {}", childTerm);
                    ParameterComparisonResult result = lookupService.isChildOfOrSame(parentTerm, childTerm);
                    log.debug("Comparison result: {}", result);
                    switch (result) {
                        case CHILD_OF:
                        case IDENTICAL:
                            return true;
                        default:
                            return false;
                    }
                }).collect(Collectors.toList());
            }).flatMap(List::stream).filter(
                    StreamUtils.distinctByKey((term) -> {
                        return term.getIri();
                    })
            ).collect(Collectors.toList());
    }

    public List<Term> suggestParameters(String partialParamName, String parentRuleId, int levels) {
        if (ruleIdLookup.containsKey(parentRuleId)) {
            CvMappingRule rule = ruleIdLookup.get(parentRuleId);
            return suggestParameters(partialParamName, rule, levels);
        }
        return Collections.emptyList();
    }

}
