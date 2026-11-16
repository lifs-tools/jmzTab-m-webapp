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
package org.lifstools.mztab.validator.engine.fieldrules;

import info.psidev.cvmapping.CvMapping;
import info.psidev.cvmapping.CvMappingRule;
import info.psidev.cvmapping.CvTerm;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.jxpath.JXPathContext;
import org.lifstools.mztab.validator.engine.InvalidMappingFileException;
import org.lifstools.mztab.validator.engine.SafeCvMappingLoader;
import org.lifstools.mztab.validator.engine.api.FieldRule;
import org.lifstools.mztab.validator.engine.api.FieldRuleTerm;
import org.lifstools.mztab.validator.engine.api.FieldRules;
import org.lifstools.mztab2.cvmapping.JxPathElement;
import org.lifstools.mztab2.io.MzTabFileParser;
import org.lifstools.mztab2.model.MzTab;
import org.springframework.lang.Nullable;
import uk.ac.ebi.pride.jmztab2.utils.errors.MZTabErrorType;

/**
 * Lists the rules of a CV mapping and, with a text, whether the text has the field of each rule.
 *
 * <p>A rule's field is present when its JXPath selects at least one non-null node in the parsed model. The selection
 * is jmzTab-m's own ({@link JxPathElement#toList(JXPathContext, String, Class)}, which its
 * {@code CvMappingValidator} calls for every rule); the validator's rule evaluation is private to it and also
 * checks the terms, which is not asked here.
 */
@Slf4j
public final class FieldRuleReader {

    private FieldRuleReader() {
    }

    /**
     * @param mapping a CV mapping file
     * @param text an mzTab-M text, or {@code null} for the rules alone
     * @return the rules in the order of the mapping; {@code parsed} is false, and every presence null, if there is
     * no text or no model could be built from it
     * @throws InvalidMappingFileException if the mapping is not a CV mapping
     */
    public static FieldRules read(Path mapping, @Nullable Path text) throws IOException {
        CvMapping cvMapping = SafeCvMappingLoader.load(mapping);
        if (cvMapping.getCvMappingRuleList() == null) {
            throw new InvalidMappingFileException("Mapping file rejected: it has no CvMappingRuleList", null);
        }
        MzTab model = text == null ? null : parse(text);
        JXPathContext context = model == null ? null : JXPathContext.newContext(model);
        List<FieldRule> rules = new ArrayList<>();
        for (CvMappingRule rule : cvMapping.getCvMappingRuleList().getCvMappingRule()) {
            rules.add(toRule(rule, context == null ? null : present(context, model, rule)));
        }
        return new FieldRules(model != null, rules);
    }

    /** The model of the text, or {@code null} if jmzTab-m builds none (it builds none for a text with errors). */
    @Nullable
    private static MzTab parse(Path text) {
        try {
            MzTabFileParser parser = new MzTabFileParser(text.toFile());
            parser.parse(PrintStream.nullOutputStream(), MZTabErrorType.findLevel("ERROR"), 100);
            return parser.getMZTabFile();
        } catch (Exception e) {
            log.info("The text could not be read into a model: {}", e.toString());
            return null;
        }
    }

    /**
     * Whether the model has what the rule asks for. A rule whose element is absent because its table has no rows has
     * nothing to apply to, so it counts as met, as in jmzTab-m's CV-mapping validation (CvMappingValidator, which
     * skips such a rule): a summary-only file needs no evidence rule. The table is the one the element path starts
     * with, or the one a metadata field is required with.
     */
    @Nullable
    private static Boolean present(JXPathContext context, MzTab model, CvMappingRule rule) {
        try {
            boolean found = JxPathElement.toList(context, rule.getCvElementPath(), Object.class).stream()
                .map(pair -> pair.getValue())
                .anyMatch(value -> value != null && !(value instanceof Collection<?> c && c.isEmpty()));
            return found || tableAbsent(model, rule.getCvElementPath());
        } catch (RuntimeException e) {
            log.warn("The path {} of rule {} could not be selected: {}", rule.getCvElementPath(), rule.getId(),
                e.toString());
            return null;
        }
    }

    /** Metadata fields that the profile requires only together with a table, as in jmzTab-m. */
    private static final Map<String, String> TABLE_OF_FIELD = Map.of(
        "/metadata/@idConfidenceMeasure", "smallMoleculeEvidence",
        "/metadata/@smallMoleculeQuantificationUnit", "smallMoleculeSummary",
        "/metadata/@smallMoleculeFeatureQuantificationUnit", "smallMoleculeFeature");

    private static boolean tableAbsent(MzTab model, String elementPath) {
        String table = TABLE_OF_FIELD.getOrDefault(elementPath,
            elementPath.replaceFirst("^/([^/\\[@]+).*$", "$1"));
        List<?> rows = switch (table) {
            case "smallMoleculeSummary" -> model.getSmallMoleculeSummary();
            case "smallMoleculeFeature" -> model.getSmallMoleculeFeature();
            case "smallMoleculeEvidence" -> model.getSmallMoleculeEvidence();
            default -> null;
        };
        return switch (table) {
            case "smallMoleculeSummary", "smallMoleculeFeature", "smallMoleculeEvidence" -> rows == null || rows.isEmpty();
            default -> false;
        };
    }

    private static FieldRule toRule(CvMappingRule rule, @Nullable Boolean present) {
        List<FieldRuleTerm> terms = new ArrayList<>();
        for (CvTerm term : rule.getCvTerm()) {
            terms.add(new FieldRuleTerm(term.getTermAccession(), term.getTermName(), term.isAllowChildren(),
                term.isUseTerm()));
        }
        String name = rule.getName() == null || rule.getName().isBlank() ? rule.getId() : rule.getName();
        return new FieldRule(rule.getId(), name, rule.getCvElementPath(),
            rule.getRequirementLevel() == null ? null : rule.getRequirementLevel().value(),
            rule.getCvTermsCombinationLogic() == null ? null : rule.getCvTermsCombinationLogic().value(),
            terms, present);
    }
}
