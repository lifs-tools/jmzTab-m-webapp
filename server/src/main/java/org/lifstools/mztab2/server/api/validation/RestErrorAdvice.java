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
package org.lifstools.mztab2.server.api.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.lifstools.mztab.validator.webapp.domain.ErrorResponse;
import org.lifstools.mztab.validator.webapp.session.StorageFullException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Answers errors of the validations endpoints as JSON {@link ErrorResponse} bodies. It is scoped to
 * {@link ValidationsApiController}, so the older validate endpoints keep their behaviour. Standard Spring MVC
 * exceptions keep their status codes.
 */
@RestControllerAdvice(assignableTypes = {ValidationsApiController.class, ExamplesApiController.class,
    AppInfoApiController.class})
public class RestErrorAdvice extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(RestErrorAdvice.class);

    static final String TOO_LARGE = "The uploaded file is too large.";

    @ExceptionHandler(UnknownValidationException.class)
    public ResponseEntity<ErrorResponse> notFound(UnknownValidationException e, HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, e.getMessage(), request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> badRequest(Exception e, HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, e.getMessage(), request);
    }

    @ExceptionHandler(StorageFullException.class)
    public ResponseEntity<ErrorResponse> storageFull(StorageFullException e, HttpServletRequest request) {
        return respond(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage(), request);
    }

    /** Keeps the status of standard exceptions; the body is the common JSON shape with a short message. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body, HttpHeaders headers,
        HttpStatusCode statusCode, WebRequest request) {
        HttpStatus status = HttpStatus.valueOf(statusCode.value());
        String message;
        if (ex instanceof MaxUploadSizeExceededException) {
            message = TOO_LARGE;
        } else if (ex instanceof TypeMismatchException tm) {
            message = "Invalid value for parameter '" + tm.getPropertyName() + "'.";
        } else if (ex instanceof ResponseStatusException rse && rse.getReason() != null) {
            message = rse.getReason();
        } else if (ex instanceof MissingServletRequestPartException || ex instanceof MissingServletRequestParameterException) {
            message = ex.getMessage();
        } else {
            message = status.getReasonPhrase();
        }
        String path = request instanceof ServletWebRequest swr ? swr.getRequest().getRequestURI() : null;
        HttpHeaders out = new HttpHeaders();
        out.putAll(headers);
        out.setContentType(MediaType.APPLICATION_JSON);
        return new ResponseEntity<>(new ErrorResponse(status, status.getReasonPhrase(), message, path), out, status);
    }

    static ResponseEntity<ErrorResponse> respond(HttpStatus status, @Nullable String message,
        HttpServletRequest request) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
            .body(new ErrorResponse(status, status.getReasonPhrase(), message, request.getRequestURI()));
    }

    /**
     * Multipart parsing fails before a handler is chosen, so the advice above never sees an oversized upload.
     * This resolver answers it for {@code /rest/**} only; other paths keep their behaviour.
     */
    @Component
    @Order(Ordered.HIGHEST_PRECEDENCE)
    static class UploadTooLargeResolver implements HandlerExceptionResolver {

        private final ObjectMapper mapper;

        UploadTooLargeResolver(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        @Nullable
        public ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response,
            @Nullable Object handler, Exception ex) {
            if (!(ex instanceof MaxUploadSizeExceededException) || !request.getRequestURI().startsWith(
                request.getContextPath() + "/rest/")) {
                return null;
            }
            HttpStatus status = HttpStatus.PAYLOAD_TOO_LARGE;
            response.setStatus(status.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            try {
                mapper.writeValue(response.getWriter(),
                    new ErrorResponse(status, status.getReasonPhrase(), TOO_LARGE, request.getRequestURI()));
            } catch (IOException e) {
                log.warn("Could not write the 413 response", e);
            }
            return new ModelAndView();
        }
    }
}
