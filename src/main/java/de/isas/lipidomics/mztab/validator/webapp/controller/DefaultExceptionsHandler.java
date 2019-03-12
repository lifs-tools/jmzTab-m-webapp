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
package de.isas.lipidomics.mztab.validator.webapp.controller;

import de.isas.lipidomics.mztab.validator.webapp.service.storage.StorageFileNotFoundException;
import java.util.Date;
import javax.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.NoHandlerFoundException;

/**
 *
 * @author nils.hoffmann
 */
@Slf4j
@ControllerAdvice
public class DefaultExceptionsHandler {

    private final ControllerUtils utils;

    @Autowired
    public DefaultExceptionsHandler(ControllerUtils utils) {
        this.utils = utils;
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
        log.error("MultipartException: ", exception);
        ModelAndView mav = new ModelAndView();
        mav.addObject("page", utils.createPage("mzTabValidator"));
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
        log.error("Error: ", exception);
        ModelAndView mav = new ModelAndView();
        mav.addObject("page", utils.createPage("mzTabValidator"));
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
        mav.addObject("page", utils.createPage("mzTabValidator"));
        mav.addObject("error", "Resource not found!");
        mav.addObject("url", req.getRequestURL());
        mav.addObject("timestamp", new Date().toString());
        mav.addObject("status", 404);
        mav.setViewName("error");
        return mav;
    }
}
