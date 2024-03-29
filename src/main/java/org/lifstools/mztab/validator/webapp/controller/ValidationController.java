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
package org.lifstools.mztab.validator.webapp.controller;

import org.lifstools.mztab.validator.webapp.ExampleFileConfig;
import org.lifstools.mztab.validator.webapp.domain.AppInfo;
import org.lifstools.mztab.validator.webapp.domain.Page;
import org.lifstools.mztab.validator.webapp.domain.ToolResult;
import org.lifstools.mztab.validator.webapp.domain.ToolResult.Keys;
import org.lifstools.mztab.validator.webapp.domain.UserSessionFile;
import org.lifstools.mztab.validator.webapp.domain.ValidationForm;
import org.lifstools.mztab.validator.webapp.domain.ValidationLevel;
import org.lifstools.mztab.validator.webapp.domain.ValidationResult;
import org.lifstools.mztab.validator.webapp.domain.ValidationStatistics;
import org.lifstools.mztab.validator.webapp.service.SessionIdGenerator;
import org.lifstools.mztab.validator.webapp.service.StorageService;
import org.lifstools.mztab.validator.webapp.service.StorageService.SLOT;
import org.lifstools.mztab.validator.webapp.service.ToolResultService;
import org.lifstools.mztab.validator.webapp.service.ValidationService;
import org.lifstools.mztab.validator.webapp.service.ValidationService.Status;
import org.lifstools.mztab.validator.webapp.service.storage.StorageException;
import org.lifstools.mztab.validator.webapp.service.storage.StorageFileNotFoundException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.ws.rs.QueryParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriComponents;
//import uk.ac.ebi.pride.jmztab2.utils.errors.MZTabErrorType;

/**
 *
 * @author Nils Hoffmann nils.hoffmann@cebitec.uni-bielefeld.de;
 */
@Slf4j
@Controller
public class ValidationController {

    private final StorageService storageService;
    private final ValidationService validationService;
    private final SessionIdGenerator sessionIdGenerator;
    private final ToolResultService resultService;
    private final AppInfo appInfo;
    private int maxErrors = 100;

    @Value("${minCleanupAge}")
    private Long minCleanupAge;

    @Value("${spring.servlet.multipart.max-file-size}")
    private String uploadLimit;

    private ExampleFileConfig exampleFileConfig;

    @Autowired
    public ValidationController(StorageService storageService,
        ValidationService validationService,
        SessionIdGenerator sessionIdGenerator, ToolResultService resultService,
        AppInfo appInfo, ExampleFileConfig exampleFileConfig) {
        this.storageService = storageService;
        this.validationService = validationService;
        this.sessionIdGenerator = sessionIdGenerator;
        this.resultService = resultService;
        this.appInfo = appInfo;
        this.exampleFileConfig = exampleFileConfig;
    }

    @GetMapping("/")
    public ModelAndView handleHome() throws IOException {
        ModelAndView model = new ModelAndView("index");
        model.addObject("page", createPage("mzTabValidator"));
        model.addObject("validationForm", new ValidationForm());
        model.addObject("uploadLimit", uploadLimit);
        model.addObject("exampleFiles", exampleFileConfig.getExampleFile());
        return model;
    }

    @PostMapping("/")
    public ModelAndView handleFileUpload(
        @ModelAttribute ValidationForm validationForm,
        RedirectAttributes redirectAttributes, HttpServletRequest request,
        HttpSession session) throws IOException {
        if (session == null) {
            UriComponents uri = ServletUriComponentsBuilder
                .fromServletMapping(request).
                build();
            return new ModelAndView(
                "redirect:" + uri.toUriString());
        }
        UserSessionFile usf = storageService.store(validationForm.getFile(),
            sessionIdGenerator.generate(), SLOT.MZTABFILE);
        if (storageService.load(usf, SLOT.MZTABFILE) == null) {
            throw new StorageException("Could not load user session file!");
        }
        UserSessionFile validationFile;
        if (validationForm.getMappingFile() == null || validationForm.getMappingFile().isEmpty()) {
            validationFile = storageService.
                store(new ClassPathResource("/mappings/mzTab-M-mapping.xml").getURL(), usf.
                        getSessionId(), SLOT.MAPPINGFILE);
        } else {
            validationFile = storageService.store(validationForm.
                getMappingFile(), "semantic-validation.xml", usf.getSessionId(),
                SLOT.MAPPINGFILE);
        }
        if (storageService.load(validationFile, SLOT.MAPPINGFILE) == null) {
            throw new StorageException("Could not load mapping file!");
        }
        UriComponents uri = ServletUriComponentsBuilder
            .fromServletMapping(request).
            pathSegment("validate", usf.getSessionId().
                toString()).
            queryParam("version", validationForm.getMzTabVersion()).
            queryParam("maxErrors", Math.max(1, validationForm.getMaxErrors())).
            queryParam("level", validationForm.getLevel()).
            queryParam("checkCvMapping", validationForm.getCheckCvMapping()).
            build();
        ModelAndView modelAndView = new ModelAndView(
            "redirect:" + uri.toUriString());
        return modelAndView;
    }

    @GetMapping(value = "/validate/{sessionId:.+}")
    public ModelAndView validateFile(@PathVariable UUID sessionId, @QueryParam(
        "version") ValidationService.MzTabVersion version, @QueryParam(
        "maxErrors") int maxErrors, @QueryParam("level") ValidationLevel level,
        @QueryParam("checkCvMapping") boolean checkCvMapping,
        HttpServletRequest request,
        HttpSession session) {
        if (session == null) {
            UriComponents uri = ServletUriComponentsBuilder
                .fromServletMapping(request).
                build();
            return new ModelAndView(
                "redirect:" + uri.toUriString());
        }

        Status status = validationService.getStatus(sessionId).
            getStatus();
        if (status == Status.UNINITIALIZED) {
            UserSessionFile usf = storageService.load(sessionId, SLOT.MZTABFILE);
            UserSessionFile mappingFile = storageService.load(sessionId, SLOT.MAPPINGFILE);
            ToolResult toolResult = resultService.getOrCreateResultFor(
                sessionId);
            Map<ToolResult.Keys, String> parameters = new EnumMap(
                ToolResult.Keys.class);
            parameters.putIfAbsent(ToolResult.Keys.MZTABVERSION, version.
                name());
            parameters.putIfAbsent(ToolResult.Keys.MAXERRORS, maxErrors + "");
            parameters.putIfAbsent(ToolResult.Keys.VALIDATIONLEVEL,
                level.name());
            parameters.putIfAbsent(ToolResult.Keys.CHECKCVMAPPING, Boolean.
                toString(checkCvMapping));
            toolResult.setParameters(parameters);
            resultService.addResultFor(sessionId, toolResult);
            validationService.runValidation(version, usf, maxErrors,
                level, checkCvMapping, mappingFile);
        }
        UriComponents uri = ServletUriComponentsBuilder
            .fromServletMapping(request).
            pathSegment("result", sessionId.toString()).
            build();
        return new ModelAndView(
            "redirect:" + uri.toUriString());
    }

    @GetMapping(value = "/result/{sessionId:.+}")
    public ModelAndView getResults(@PathVariable String sessionId,
        HttpServletRequest request,
        HttpSession session) throws FileNotFoundException {
        if (session == null) {
            return redirectToServletRoot(request);
        }
        ModelAndView modelAndView = new ModelAndView("validationResult");
        modelAndView.addObject("minCleanupAge", minCleanupAge);
        log.debug("Preparing result view");

        UUID userSessionId = UUID.fromString(
            sessionId);
        ToolResult result = resultService.getOrCreateResultFor(userSessionId);
        if (result == null) {
            throw new NullPointerException(
                "No results for session id " + sessionId + "!");
        }
        UserSessionFile validatedFile = storageService.load(userSessionId, SLOT.MZTABFILE);
        if (validatedFile == null) {
            throw new NullPointerException(
                "No results for session id " + sessionId + "!");
        }
        log.debug("Creating page");
        modelAndView.
            addObject("page", createPage(validatedFile.getFilename()));
        modelAndView.addObject("validationFile", validatedFile.getFilename());
        modelAndView.addObject("sessionId", sessionId);
        log.debug("Retrieving mztab version");
        ValidationService.MzTabVersion validationVersion = ValidationService.MzTabVersion.
            valueOf(result.getParameters().
                get(ToolResult.Keys.MZTABVERSION));
        if (validationVersion != null) {
            modelAndView.addObject("validationVersion", validationVersion);
        } else {
            validationVersion = ValidationService.MzTabVersion.MZTAB_2_0;
            modelAndView.addObject("validationVersion", validationVersion);
        }
        log.debug("Validation version is {}", validationVersion);
        Integer maxErrors = Integer.parseInt(result.getParameters().
            getOrDefault(Keys.MAXERRORS, "100"));
        if (maxErrors >= 0) {
            modelAndView.
                addObject("validationMaxErrors", Math.max(1, maxErrors));
        } else {
            modelAndView.addObject("validationMaxErrors", 100);
        }
        log.debug("Max errors are {}", maxErrors);
        ValidationLevel validationLevel = ValidationLevel.INFO;
        ValidationLevel level = ValidationLevel.valueOf(result.getParameters().
            getOrDefault(Keys.VALIDATIONLEVEL, "INFO"));
        if (level != null) {
            validationLevel = level;
        }
        modelAndView.addObject("validationLevel", validationLevel);
        log.debug("Validation level is {}", validationLevel);
        boolean checkCvMapping = Boolean.parseBoolean(result.getParameters().
            getOrDefault(Keys.CHECKCVMAPPING, "false"));
        modelAndView.addObject("checkCvMapping", checkCvMapping);
        log.debug("Check cv mapping is {}", checkCvMapping);
        modelAndView.addObject("status", result.getStatus());
        log.debug("Current status is {}", result.getStatus());
        switch (result.getStatus()) {
            case FAILED:
                modelAndView.addObject("progress", 0);
                modelAndView.addObject("message", result.getException().
                    getMessage());
                modelAndView.addObject("messageLevel", "alert-danger");
                addValidationResults(modelAndView, validationService.
                    asValidationResults(result.getMessages()), level, maxErrors,
                    validationVersion, validatedFile, validationLevel);
                break;
            case PREPARING:
                modelAndView.addObject("progress", 10);
                modelAndView.addObject("refresh", 1);
                break;
            case STARTED:
                modelAndView.addObject("progress", 25);
                modelAndView.addObject("refresh", 2);
                break;
            case RUNNING:
                modelAndView.addObject("progress", 50);
                modelAndView.addObject("refresh", 5);
                break;
            case FINISHED:
                modelAndView.addObject("progress", 100);
                addValidationResults(modelAndView, validationService.
                    asValidationResults(result.getMessages()), level, maxErrors,
                    validationVersion, validatedFile, validationLevel);
                break;
            default:
                modelAndView.addObject("progress", 0);
                modelAndView.addObject("refresh", 1);
        }
        return modelAndView;
    }

    protected void addValidationResults(ModelAndView modelAndView,
        List<ValidationResult> validationResults, ValidationLevel level,
        Integer maxErrors1, ValidationService.MzTabVersion validationVersion,
        UserSessionFile usf, ValidationLevel validationLevel) {
        ValidationStatistics vs = new ValidationStatistics(
            validationResults);
        modelAndView.addObject("validationStatistics", vs);
        modelAndView.addObject("noWarningsOrErrors", vs.getNoErrorsOrWarnings());
        modelAndView.addObject("validationResults",
            validationService.
                filterByLevel(validationResults, level).
                subList(0,
                    Math.min(validationResults.size(), maxErrors1)));
        Map<String, List<Map<String, String>>> mzTabContents = validationService.
            parse(validationVersion,
                usf, maxErrors1, validationLevel);
        if (validationVersion == ValidationService.MzTabVersion.MZTAB_2_0) {
            addDataRowsFor(modelAndView, mzTabContents, "META");
            addDataRowsFor(modelAndView, mzTabContents, "SUMMARY");
            addDataRowsFor(modelAndView, mzTabContents, "FEATURE");
            addDataRowsFor(modelAndView, mzTabContents, "EVIDENCE");
        } else {
            addDataRowsFor(modelAndView, mzTabContents, "META");
            addDataRowsFor(modelAndView, mzTabContents, "PROTEINS");
            addDataRowsFor(modelAndView, mzTabContents, "PEPTIDES");
            addDataRowsFor(modelAndView, mzTabContents, "PSMS");
        }
    }

    protected void addDataRowsFor(ModelAndView modelAndView,
        Map<String, List<Map<String, String>>> mzTabContents, String key) {
        modelAndView.addObject(key.toLowerCase() + "DataRows", mzTabContents.
            getOrDefault(key, Collections.emptyList()));
        Collection<String> featureKeys = mzTabContents.getOrDefault(key,
            Collections.emptyList()).
            stream().
            findFirst().
            map((t) ->
            {
                return t.keySet();
            }).
            orElse(Collections.emptySet());
        modelAndView.
            addObject(key.toLowerCase() + "DataColumnKeys", featureKeys);
    }

    @GetMapping("/about")
    public ModelAndView handleAbout() {
        ModelAndView model = new ModelAndView("about");
        model.addObject("page", createPage("About"));
        return model;
    }

    private ModelAndView redirectToServletRoot(HttpServletRequest request) {
        UriComponents uri = ServletUriComponentsBuilder
            .fromServletMapping(request).
            build();
        log.info("Redirecting to {}", uri.toUriString());
        ModelAndView mav = new ModelAndView("redirect:" + uri.toUriString());
        return mav;
    }

    @GetMapping(value = "/validate/{sessionId:.+}/delete")
    public ModelAndView deleteResults(@PathVariable UUID sessionId,
        HttpServletRequest request,
        HttpSession session, RedirectAttributes redirectAttrs) {
        if (session == null) {
            return redirectToServletRoot(request);
        }
        if (sessionId == null) {
            throw new IllegalArgumentException("Please supply your session-id!");
        }
        storageService.deleteAll(sessionId);
        resultService.deleteResultFor(sessionId);
        redirectAttrs.addFlashAttribute("message",
            "Files for session " + sessionId.toString() + " have been deleted!");
        redirectAttrs.addFlashAttribute("messageLevel", "alert-success");
        return redirectToServletRoot(request);
    }

    @ExceptionHandler(StorageFileNotFoundException.class)
    public ResponseEntity<?> handleStorageFileNotFound(
        StorageFileNotFoundException exc) {
        return ResponseEntity.notFound().
            build();
    }

    @ExceptionHandler(MultipartException.class)
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    public ModelAndView handleMultipartError(HttpServletRequest req,
        MultipartException exception)
        throws Exception {
        log.error("Caught exception in controller:", exception);
        ModelAndView mav = new ModelAndView();
        mav.addObject("page", createPage("mzTabValidator"));
        mav.addObject("error", exception);
        mav.addObject("url", req.getRequestURL());
        mav.addObject("timestamp", new Date().toString());
        mav.addObject("status", 413);
        mav.setViewName("error");
        return mav;
    }

    @ExceptionHandler(Exception.class)
    public ModelAndView handleError(HttpServletRequest req, Exception exception)
        throws Exception {
        log.error("Caught exception in controller:", exception);
        ModelAndView mav = new ModelAndView();
        mav.addObject("page", createPage("mzTabValidator"));
        mav.addObject("error", exception);
        mav.addObject("url", req.getRequestURL());
        mav.addObject("timestamp", new Date().toString());
        mav.addObject("status", 500);
        mav.setViewName("error");
        return mav;
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ModelAndView handleUnmapped(HttpServletRequest req) {
        ModelAndView mav = new ModelAndView();
        mav.addObject("page", createPage("mzTabValidator"));
        mav.addObject("error", "Resource not found!");
        mav.addObject("url", req.getRequestURL());
        mav.addObject("timestamp", new Date().toString());
        mav.addObject("status", 404);
        mav.setViewName("error");
        return mav;
    }

    protected Page createPage(String title) {
        return new Page(title, appInfo);
    }

}
