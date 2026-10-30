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
import java.util.Set;
import org.lifstools.mztab.validator.webapp.engine.EngineBusyException;
import org.lifstools.mztab.validator.webapp.engine.EngineMessages;
import org.lifstools.mztab.validator.webapp.engine.EngineUnavailableException;
import org.lifstools.mztab.validator.webapp.service.validation.SynchronousLimiter;
import org.lifstools.mztab2.server.api.ConvertApiController;
import org.lifstools.mztab2.server.api.ValidateApiController;
import org.lifstools.mztab2.server.api.ValidatePlainApiController;
import org.lifstools.mztab.validator.webapp.domain.ErrorResponse;
import org.lifstools.mztab.validator.webapp.session.StorageFullException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Answers errors of the REST endpoints as JSON {@link ErrorResponse} bodies. Standard Spring MVC exceptions keep
 * their status codes.
 */
@RestControllerAdvice(assignableTypes = {ValidationsApiController.class, ExamplesApiController.class,
    AppInfoApiController.class, ValidationProfilesApiController.class, ConvertApiController.class, ValidateApiController.class,
    ValidatePlainApiController.class})
public class RestErrorAdvice extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(RestErrorAdvice.class);

    static final String TOO_LARGE = "The uploaded file is too large.";

    /** When a client may retry a request that found no free slot. */
    static final String RETRY_AFTER_SECONDS = "5";

    @ExceptionHandler(UnknownValidationException.class)
    public ResponseEntity<ErrorResponse> notFound(UnknownValidationException e, HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, e.getMessage(), request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> badRequest(Exception e, HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, e.getMessage(), request);
    }

    @ExceptionHandler(SynchronousLimiter.BusyException.class)
    public ResponseEntity<ErrorResponse> busy(SynchronousLimiter.BusyException e, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
            .contentType(MediaType.APPLICATION_JSON).body(new ErrorResponse(HttpStatus.SERVICE_UNAVAILABLE,
                HttpStatus.SERVICE_UNAVAILABLE.getReasonPhrase(), e.getMessage(), request.getRequestURI()));
    }

    /** The engine of the file's line is busy: the same answer as no free slot here, with the engine's delay. */
    @ExceptionHandler(EngineBusyException.class)
    public ResponseEntity<ErrorResponse> engineBusy(EngineBusyException e, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .header(HttpHeaders.RETRY_AFTER, Long.toString(e.retryAfter().toSeconds()))
            .contentType(MediaType.APPLICATION_JSON).body(new ErrorResponse(HttpStatus.SERVICE_UNAVAILABLE,
                HttpStatus.SERVICE_UNAVAILABLE.getReasonPhrase(), SynchronousLimiter.BUSY, request.getRequestURI()));
    }

    @ExceptionHandler(EngineUnavailableException.class)
    public ResponseEntity<ErrorResponse> engineUnavailable(EngineUnavailableException e, HttpServletRequest request) {
        log.warn("{} {}: the engine for mzTab-M {} is unavailable: {}", request.getMethod(), request.getRequestURI(),
            e.line(), e.getCause() == null ? e.getMessage() : e.getCause().toString());
        return respond(HttpStatus.SERVICE_UNAVAILABLE, EngineMessages.unavailable(e.line()), request);
    }

    @ExceptionHandler(StorageFullException.class)
    public ResponseEntity<ErrorResponse> storageFull(StorageFullException e, HttpServletRequest request) {
        return respond(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage(), request);
    }

    /**
     * A parameter outside its constraints, e.g. {@code maxErrors=501}: 400 with the common JSON shape, naming the
     * parameter and the constraint.
     */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
        HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        StringBuilder message = new StringBuilder();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            String name = result.getMethodParameter().getParameterName();
            RequestParam param = result.getMethodParameter().getParameterAnnotation(RequestParam.class);
            if (param != null && !param.name().isEmpty()) {
                name = param.name();
            }
            for (MessageSourceResolvable error : result.getResolvableErrors()) {
                if (!message.isEmpty()) {
                    message.append(' ');
                }
                message.append("Invalid value for parameter '").append(name).append("': ")
                    .append(error.getDefaultMessage()).append('.');
            }
        }
        if (message.isEmpty()) {
            message.append("Invalid request parameters.");
        }
        HttpStatus badRequest = HttpStatus.BAD_REQUEST;
        String path = request instanceof ServletWebRequest swr ? swr.getRequest().getRequestURI() : null;
        HttpHeaders out = new HttpHeaders();
        out.putAll(headers);
        out.setContentType(MediaType.APPLICATION_JSON);
        return new ResponseEntity<>(new ErrorResponse(badRequest, badRequest.getReasonPhrase(), message.toString(),
            path), out, badRequest);
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
     * Some errors happen before a handler is chosen, so the advice above never sees them: an oversized upload
     * (multipart parsing), a path with no endpoint, and a method an endpoint does not support. This resolver answers
     * them as JSON for {@code /rest/**} only; other paths keep their behaviour.
     */
    @Component
    @Order(Ordered.HIGHEST_PRECEDENCE)
    static class RestPathErrorResolver implements HandlerExceptionResolver {

        static final String NO_ENDPOINT = "There is no endpoint at this path.";
        static final String METHOD_NOT_ALLOWED = "This endpoint does not support the request method.";

        private final ObjectMapper mapper;

        RestPathErrorResolver(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        @Nullable
        public ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response,
            @Nullable Object handler, Exception ex) {
            if (!request.getRequestURI().startsWith(request.getContextPath() + "/rest/")) {
                return null;
            }
            if (ex instanceof MaxUploadSizeExceededException) {
                return write(request, response, HttpStatus.PAYLOAD_TOO_LARGE, TOO_LARGE);
            }
            if (ex instanceof NoResourceFoundException || ex instanceof NoHandlerFoundException) {
                return write(request, response, HttpStatus.NOT_FOUND, NO_ENDPOINT);
            }
            if (ex instanceof HttpRequestMethodNotSupportedException notSupported) {
                Set<HttpMethod> allowed = notSupported.getSupportedHttpMethods();
                if (allowed != null && !allowed.isEmpty()) {
                    response.setHeader(HttpHeaders.ALLOW, StringUtils.collectionToCommaDelimitedString(allowed));
                }
                return write(request, response, HttpStatus.METHOD_NOT_ALLOWED, METHOD_NOT_ALLOWED);
            }
            return null;
        }

        private ModelAndView write(HttpServletRequest request, HttpServletResponse response, HttpStatus status,
            String message) {
            response.setStatus(status.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            try {
                mapper.writeValue(response.getWriter(),
                    new ErrorResponse(status, status.getReasonPhrase(), message, request.getRequestURI()));
            } catch (IOException e) {
                log.warn("Could not write the {} response", status.value(), e);
            }
            return new ModelAndView();
        }
    }
}
