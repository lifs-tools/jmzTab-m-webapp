/*
 * Copyright 2017 Leibniz Institut für Analytische Wissenschaften - ISAS e.V..
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
package de.isas.lipidomics.mztab.validator.webapp.controller;

import de.isas.lipidomics.mztab.validator.webapp.service.StorageService;
import de.isas.lipidomics.mztab.validator.webapp.domain.UserSessionFile;
import de.isas.lipidomics.mztab.validator.webapp.service.SessionIdGenerator;
import de.isas.lipidomics.mztab.validator.webapp.service.StorageService.SLOT;
import de.isas.lipidomics.mztab.validator.webapp.service.cvcompletion.OlsMappingCvSuggestionService;
import de.isas.lipidomics.mztab.validator.webapp.service.storage.StorageException;
import de.isas.mztab2.io.MzTabFileParser;
import de.isas.mztab2.io.MzTabNonValidatingWriter;
import de.isas.mztab2.model.CV;
import de.isas.mztab2.model.Metadata;
import de.isas.mztab2.model.MzTab;
import de.isas.mztab2.model.SmallMoleculeEvidence;
import de.isas.mztab2.model.SmallMoleculeFeature;
import de.isas.mztab2.model.SmallMoleculeSummary;
import de.isas.mztab2.model.ValidationMessage;
import info.psidev.cvmapping.CvReference;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.validation.constraints.NotNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriComponents;
import uk.ac.ebi.pride.jmztab2.model.MZTabConstants;
import uk.ac.ebi.pride.jmztab2.utils.errors.MZTabErrorList;
import uk.ac.ebi.pride.jmztab2.utils.errors.MZTabErrorType;
import uk.ac.ebi.pride.utilities.ols.web.service.model.Ontology;
//import uk.ac.ebi.pride.jmztab2.utils.errors.MZTabErrorType;

/**
 *
 * @author Nils Hoffmann &lt;nils.hoffmann@isas.de&gt;
 */
@Slf4j
@Controller
public class MzTabController {

    private final StorageService storageService;
    private final SessionIdGenerator sessionIdGenerator;
    private final ControllerUtils utils;
    private final OlsMappingCvSuggestionService mappingService;
    private final int maxErrors = 100;

    @Autowired
    public MzTabController(StorageService storageService,
            SessionIdGenerator sessionIdGenerator,
            ControllerUtils utils,
            OlsMappingCvSuggestionService mappingService) {
        this.storageService = storageService;
        this.sessionIdGenerator = sessionIdGenerator;
        this.utils = utils;
        this.mappingService = mappingService;
    }

    @GetMapping(value = "/create/{sessionId:.+}")
    public ModelAndView getCreateMzTab(@PathVariable UUID sessionId, HttpServletRequest request,
            HttpSession session,
            RedirectAttributes redirectAttrs) throws IOException {
        if (session == null) {
            return utils.redirectToServletRoot(request);
        }
        if (sessionId == null) {
            throw new IllegalArgumentException("Please supply your session-id!");
        }
        UserSessionFile storedFile;
        MzTab mzTab;
        ModelAndView modelAndView = new ModelAndView("mzTab");
        try {
            storedFile = storageService.load(sessionId, SLOT.MZTABFILE);
            Path path = storageService.load(storedFile, SLOT.MZTABFILE);
            MzTabFileParser parser = new MzTabFileParser(path.toFile());
            MZTabErrorList errors = null;
            try {
                errors = parser.parse(System.out, MZTabErrorType.Level.Info, 100);
            } catch (RuntimeException ex) {
                log.error("Caught exception while trying to parse " + path + "!", ex);
            } catch (IOException ex) {
                log.error("Caught IO exception while trying to parse " + path + "!", ex);
            } finally {
                if (errors != null) {
                    List<ValidationMessage> validationResults = errors.convertToValidationMessages();
                    modelAndView.addObject("validationResults", validationResults);
                }
                mzTab = parser.getMZTabFile();
            }
        } catch (StorageException se) {
            log.warn("Could not find mzTab file for {}. Creating new one!", sessionId);
            mzTab = getEmptyMzTab(sessionId);
            MzTabNonValidatingWriter writer = new MzTabNonValidatingWriter();
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                try (OutputStreamWriter osw = new OutputStreamWriter(
                        baos, Charset.forName("UTF8"))) {
                    writer.write(osw, mzTab);
                    osw.flush();
                }
                log.info("Current file contents: {}", new String(baos.toByteArray()));
                storageService.store(new String(baos.toByteArray()), sessionId, SLOT.MZTABFILE);
            } catch (IOException ex) {
                log.error("Caught IO exception while trying to store mzTab file for sessionId='" + sessionId + "'!", ex);
            }
        }

        modelAndView.addObject("page", utils.createPage("mzTab"));
        modelAndView.addObject("sessionId", sessionId);
        modelAndView.addObject("mzTab", mzTab);
        addProperties(modelAndView);
        return modelAndView;
    }

    @ModelAttribute("mzTab")
    public MzTab getEmptyMzTab() {
        return getEmptyMzTab(UUID.randomUUID());
    }

    private MzTab getEmptyMzTab(UUID uuid) {
        MzTab mzTab = new MzTab();
        Metadata metadata = new Metadata();
        metadata.mzTabID("MZTAB-" + uuid);
        String prop = Metadata.class.getSimpleName().toLowerCase() + "." + Metadata.Properties.mzTabID.name();
        log.debug("Trying to find rule for property {}", prop);
        mappingService.getRuleForProperty(prop).ifPresent((rule) -> {
            log.debug("Using property {}, found rule {}", prop, rule.getId());
        });
        metadata.mzTabVersion(MZTabConstants.VERSION_MZTAB_M);
        metadata.title("Please replace with real title");
        metadata.description("Please replace with real description");
        List<CvReference> cvReferences = mappingService.getCvReferences();
        IntStream.range(0, cvReferences.size())
                .forEach(idx
                        -> {
                    CvReference ref = cvReferences.get(idx);
                    Ontology ontology = mappingService.resolveCv(ref.getCvIdentifier());
                    log.debug("Retrieved ontology {} for query {}", ontology, ref.getCvIdentifier());
                    metadata.addCvItem(new CV().id(++idx).
                            fullName(ontology.getDescription().replaceAll("\t", " ")).
                            label(ref.getCvIdentifier()).
                            uri("https://www.ebi.ac.uk/ols/ontologies/" + ref.getCvIdentifier().toLowerCase()).
                            version(ontology.getVersion()));
                }
                );
        String prop2 = Metadata.class.getSimpleName().toLowerCase() + "." + Metadata.Properties.smallMoleculeFeatureQuantificationUnit.name();
        log.debug("Trying to find rule for property {}", prop2);
        mappingService.getRuleForProperty(prop2).ifPresent((rule) -> {
            log.debug("Using property {}, found rule {}", prop2, rule.getId());
        });
        mzTab.metadata(metadata);
        return mzTab;
    }

    @PostMapping(value = "/create/{sessionId:.+}")
    public ModelAndView postCreateMzTab(@PathVariable UUID sessionId,
            @NotNull MzTab mzTab,
            HttpServletRequest request,
            HttpSession session,
            RedirectAttributes redirectAttrs, BindingResult bindingResult) {
        if (session == null) {
            return utils.redirectToServletRoot(request);
        }
        if (sessionId == null) {
            throw new IllegalArgumentException("Please supply your session-id!");
        }
//        if (bindingResult.hasErrors()) {
//            ModelAndView modelAndView = new ModelAndView("mzTab");
//            modelAndView.addObject("page", createPage("mzTab"));
//            modelAndView.addObject("sessionId", sessionId);
//            modelAndView.addObject("mzTab", mzTab);
//            modelAndView.addObject("metadataProperties", Metadata.Properties.values());
//            modelAndView.addObject("smlProperties", SmallMoleculeSummary.Properties.values());
//            modelAndView.addObject("smfProperties", SmallMoleculeFeature.Properties.values());
//            modelAndView.addObject("smeProperties", SmallMoleculeEvidence.Properties.values());
//            return modelAndView;
//        }
        MzTabNonValidatingWriter writer = new MzTabNonValidatingWriter();
        UserSessionFile createdFile = null;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            try (OutputStreamWriter osw = new OutputStreamWriter(
                    baos, Charset.forName("UTF8"))) {
                writer.write(osw, mzTab);
                osw.flush();
            }
            log.info("Current file contents: {}", new String(baos.toByteArray()));
            createdFile = storageService.store(new String(baos.toByteArray()), sessionId, SLOT.MZTABFILE);
        } catch (IOException ex) {
            log.error("Caught IO exception while trying to store mzTab file for sessionId='" + sessionId + "'!", ex);
        }
        Path validatedFile = storageService.load(createdFile, SLOT.MZTABFILE);
        if (validatedFile == null) {
            throw new NullPointerException(
                    "Could not find mzTab file for session id " + sessionId + "!");
        }
        MzTabFileParser parser = new MzTabFileParser(validatedFile.toFile());
        ModelAndView modelAndView = new ModelAndView("mzTab");
        modelAndView.addObject("page", utils.createPage("mzTab"));
        modelAndView.addObject("sessionId", sessionId);
        MZTabErrorList errors = null;
        try (ByteArrayOutputStream errorOut = new ByteArrayOutputStream()) {
            errors = parser.parse(errorOut, MZTabErrorType.Level.Info, 100);
        } catch (RuntimeException ex) {
            log.error("Caught runtime exception while trying to parse " + validatedFile + "!", ex);
        } catch (IOException ex) {
            log.error("Caught IO exception while trying to parse " + validatedFile + "!", ex);
        } catch (Exception ex) {
            log.error("Caught exception while trying to parse " + validatedFile + "!", ex);
        } finally {
            if (errors != null) {
                List<ValidationMessage> validationResults = errors.convertToValidationMessages();
                modelAndView.addObject("validationResults", validationResults);
            }
        }
        //MzTab m = parser.getMZTabFile();
        modelAndView.addObject("mzTab", parser.getMZTabFile() == null ? mzTab : parser.getMZTabFile());
        addProperties(modelAndView);
//        }
        return modelAndView;
    }

    private void addProperties(ModelAndView modelAndView) {
        modelAndView.addObject("metadataProperties", Metadata.Properties.values());
        modelAndView.addObject("metadataGeneralProperties", Arrays.stream(Metadata.Properties.values()).filter((prop) -> {
            switch (prop) {
                case contact:
                case description:
                case mzTabID:
                case mzTabVersion:
                case title:
                    return true;
                default:
                    return false;
            }
        }).collect(Collectors.toList()));
        modelAndView.addObject("metadataDefinitionsProperties", Arrays.stream(Metadata.Properties.values()).filter((prop) -> {
            switch (prop) {
                case cv:
                case database:
                case smallMoleculeFeatureQuantificationUnit:
                case smallMoleculeQuantificationUnit:
                case smallMoleculeIdentificationReliability:
                case colunitSmallMolecule:
                case colunitSmallMoleculeFeature:
                case colunitSmallMoleculeEvidence:
                    return true;
                default:
                    return false;
            }
        }).collect(Collectors.toList()));
        modelAndView.addObject("metadataStudyProperties", Arrays.stream(Metadata.Properties.values()).filter((prop) -> {
            switch (prop) {
                case uri:
                case externalStudyUri:
                case studyVariable:
                case smallMoleculeFeatureQuantificationUnit:
                case smallMoleculeQuantificationUnit:
                case smallMoleculeIdentificationReliability:
                    return true;
                default:
                    return false;
            }
        }).collect(Collectors.toList()));
        modelAndView.addObject("smlProperties", SmallMoleculeSummary.Properties.values());
        modelAndView.addObject("smfProperties", SmallMoleculeFeature.Properties.values());
        modelAndView.addObject("smeProperties", SmallMoleculeEvidence.Properties.values());
    }

    @GetMapping(value = "/create")
    public ModelAndView handleCreateMzTab(
            HttpServletRequest request,
            HttpSession session, RedirectAttributes redirectAttrs) {
        if (session == null) {
            return utils.redirectToServletRoot(request);
        }
        UUID sessionId = sessionIdGenerator.generate();
        if (sessionId == null) {
            throw new IllegalArgumentException("Please supply your session-id!");
        }
        UriComponents uri = ServletUriComponentsBuilder
                .fromServletMapping(request).
                pathSegment("create", sessionId.toString()).
                build();
        ModelAndView modelAndView = new ModelAndView("redirect:" + uri.toUriString());
        return modelAndView;
    }

    @GetMapping(value = "/create/{sessionId:.+}/mztab")
    @ResponseBody
    public ResponseEntity<Resource> getMzTabFile(
            @PathVariable String sessionId,
            HttpServletRequest request,
            HttpSession session) throws FileNotFoundException {
        UUID resultSessionId = UUID.fromString(sessionId);
        MediaType mediaType = MediaType.TEXT_PLAIN;
        UserSessionFile usf = storageService.load(resultSessionId, SLOT.MZTABFILE);

        Resource file = storageService.loadAsResource(usf, SLOT.MZTABFILE);
        return ResponseEntity.ok().
                header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + sessionId + ".mzTab" + "\"").
                contentType(mediaType).
                body(file);
    }

}
