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

import de.isas.lipidomics.mztab.validator.webapp.domain.Page;
import de.isas.lipidomics.mztab.validator.webapp.domain.ValidationForm;
import de.isas.lipidomics.mztab.validator.webapp.service.StorageService;
import de.isas.lipidomics.mztab.validator.webapp.service.ValidationService;
import de.isas.lipidomics.mztab.validator.webapp.service.storage.StorageFileNotFoundException;
import de.isas.lipidomics.mztab.validator.webapp.domain.UserSessionFile;
import de.isas.lipidomics.mztab.validator.webapp.domain.ValidationLevel;
import java.io.IOException;
import java.util.Date;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.ws.rs.QueryParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
//import uk.ac.ebi.pride.jmztab1_1.utils.errors.MZTabErrorType;

/**
 *
 * @author Nils Hoffmann &lt;nils.hoffmann@isas.de&gt;
 */
@Controller
public class ValidationController {

    private final StorageService storageService;
    private final ValidationService validationService;
    private int maxErrors = 100;

    @Value("${version.number}")
    private String versionNumber;

    @Value("${ga.id}")
    private String gaId;
    
    @Value("${jmztab.version.number}")
    private String jmztabVersionNumber;

    @Value("${jmztabm.version.number}")
    private String jmztabmVersionNumber;
    
    @Autowired
    public ValidationController(StorageService storageService,
        ValidationService validationService) {
        this.storageService = storageService;
        this.validationService = validationService;
    }

    @GetMapping("/")
    public ModelAndView listUploadedFiles() throws IOException {
        ModelAndView model = new ModelAndView("index");
        model.addObject("page", new Page("mzTabValidator", versionNumber, gaId, jmztabVersionNumber, jmztabmVersionNumber));
        model.addObject("validationForm", new ValidationForm());
        return model;
    }

    @PostMapping("/")
    public ModelAndView handleFileUpload(
        @ModelAttribute ValidationForm validationForm,
        RedirectAttributes redirectAttributes, HttpServletRequest request,
        HttpSession session) {
        if (session == null) {
            UriComponents uri = ServletUriComponentsBuilder
                .fromServletMapping(request).
                build();
            return new ModelAndView(
                "redirect:" + uri.toUriString());
        }
        String sessionId = session.getId();
        UserSessionFile usf = storageService.store(validationForm.getFile(),
            sessionId);
        UriComponents uri = ServletUriComponentsBuilder
            .fromServletMapping(request).
            pathSegment("validate", usf.getFilename()).
            queryParam("version", validationForm.getMzTabVersion()).
            queryParam("maxErrors", Math.max(1, validationForm.getMaxErrors())).
            queryParam("level", validationForm.getLevel()).
            build();
        ModelAndView modelAndView = new ModelAndView(
            "redirect:" + uri.toUriString());
        return modelAndView;
    }

    @GetMapping(value = "/validate/{filename:.+}")
    public ModelAndView validateFile(@PathVariable String filename, @QueryParam(
        "version") ValidationService.MzTabVersion version, @QueryParam(
        "maxErrors") int maxErrors, @QueryParam("level") ValidationLevel level, HttpServletRequest request,
        HttpSession session) {
        if (session == null) {
            UriComponents uri = ServletUriComponentsBuilder
                .fromServletMapping(request).
                build();
            return new ModelAndView(
                "redirect:" + uri.toUriString());
        }
        ModelAndView modelAndView = new ModelAndView("validationResult");
        modelAndView.
            addObject("page", new Page("mzTabValidator", versionNumber, gaId, jmztabVersionNumber, jmztabmVersionNumber));
        modelAndView.addObject("validationFile", filename);
        ValidationService.MzTabVersion validationVersion = version;
        if (validationVersion != null) {
            modelAndView.addObject("validationVersion", validationVersion);
        } else {
            validationVersion = ValidationService.MzTabVersion.MZTAB_1_1;
            modelAndView.addObject("validationVersion", validationVersion);
        }
        if (maxErrors >= 0) {
            modelAndView.addObject("validationMaxErrors", Math.max(1, maxErrors));
        } else {
            modelAndView.addObject("validationMaxErrors", 100);
        }
        ValidationLevel validationLevel = ValidationLevel.INFO;
        if(level!=null) {
            validationLevel = level;
        }
        modelAndView.addObject("validationLevel", validationLevel);
        UserSessionFile usf = new UserSessionFile(filename, session.getId());
        modelAndView.addObject("validationResults", validationService.
            asValidationResults(validationService.validate(
                validationVersion, usf, maxErrors, validationLevel)));
        return modelAndView;
    }

    @ExceptionHandler(StorageFileNotFoundException.class)
    public ResponseEntity<?> handleStorageFileNotFound(
        StorageFileNotFoundException exc) {
        return ResponseEntity.notFound().
            build();
    }

    @ExceptionHandler(MultipartException.class)
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    public ModelAndView handleMultipartError(HttpServletRequest req, MultipartException exception)
        throws Exception {
        ModelAndView mav = new ModelAndView();
        mav.addObject("page", new Page("mzTabValidator", versionNumber, gaId, jmztabVersionNumber, jmztabmVersionNumber));
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
        ModelAndView mav = new ModelAndView();
        mav.addObject("page", new Page("mzTabValidator", versionNumber, gaId, jmztabVersionNumber, jmztabmVersionNumber));
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
        mav.addObject("page", new Page("mzTabValidator", versionNumber, gaId, jmztabVersionNumber, jmztabmVersionNumber));
        mav.addObject("error", "Resource not found!");
        mav.addObject("url", req.getRequestURL());
        mav.addObject("timestamp", new Date().toString());
        mav.addObject("status", 404);
        mav.setViewName("error");
        return mav;
    }

}
