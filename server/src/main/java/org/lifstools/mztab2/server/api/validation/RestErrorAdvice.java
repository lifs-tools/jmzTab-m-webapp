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
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

/**
 * Answers errors of the REST API as JSON {@link ErrorResponse} bodies.
 */
@RestControllerAdvice(basePackages = "org.lifstools.mztab2.server.api")
public class RestErrorAdvice {

    private static final Logger log = LoggerFactory.getLogger(RestErrorAdvice.class);

    @ExceptionHandler(UnknownValidationException.class)
    public ResponseEntity<ErrorResponse> notFound(UnknownValidationException e, HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, e.getMessage(), request);
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class,
        MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class,
        MissingServletRequestPartException.class})
    public ResponseEntity<ErrorResponse> badRequest(Exception e, HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, e.getMessage(), request);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> status(ResponseStatusException e, HttpServletRequest request) {
        HttpStatus status = HttpStatus.valueOf(e.getStatusCode().value());
        return respond(status, e.getReason() == null ? status.getReasonPhrase() : e.getReason(), request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> methodNotAllowed(Exception e, HttpServletRequest request) {
        return respond(HttpStatus.METHOD_NOT_ALLOWED, e.getMessage(), request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> unsupportedMediaType(Exception e, HttpServletRequest request) {
        return respond(HttpStatus.UNSUPPORTED_MEDIA_TYPE, e.getMessage(), request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> tooLarge(Exception e, HttpServletRequest request) {
        return respond(HttpStatus.PAYLOAD_TOO_LARGE, TOO_LARGE, request);
    }

    @ExceptionHandler(StorageFullException.class)
    public ResponseEntity<ErrorResponse> storageFull(StorageFullException e, HttpServletRequest request) {
        return respond(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> unexpected(Exception e, HttpServletRequest request) {
        log.error("Unexpected error in REST API", e);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error.", request);
    }

    static final String TOO_LARGE = "The uploaded file is too large.";

    static ResponseEntity<ErrorResponse> respond(HttpStatus status, @Nullable String message,
        HttpServletRequest request) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
            .body(new ErrorResponse(status, status.getReasonPhrase(), message, request.getRequestURI()));
    }

    /**
     * Multipart parsing fails before a handler is chosen, so the package-restricted advice above never sees an
     * oversized upload. This resolver answers it for {@code /rest/**} only; other paths keep their behaviour.
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
