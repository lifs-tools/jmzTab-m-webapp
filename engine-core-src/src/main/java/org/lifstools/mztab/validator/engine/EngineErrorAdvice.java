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
package org.lifstools.mztab.validator.engine;

import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.lifstools.mztab.validator.engine.api.EngineError;
import org.lifstools.mztab.validator.engine.api.EngineErrorCode;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

/**
 * Answers every failed engine request with an {@link EngineError}: a bad
 * parameter is {@code invalid_request}, an upload over the limit
 * {@code file_too_large}, and anything unexpected {@code internal}.
 */
@Slf4j
@RestControllerAdvice
public class EngineErrorAdvice {

    /** Seconds a client waits before it retries a busy engine. */
    static final String RETRY_AFTER_SECONDS = "5";

    @ExceptionHandler(EngineException.class)
    public ResponseEntity<EngineError> refused(EngineException e) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(e.status()).contentType(MediaType.APPLICATION_JSON);
        if (e.code() == EngineErrorCode.BUSY) {
            response.header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
        }
        return response.body(new EngineError(e.code(), e.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<EngineError> tooLarge(MaxUploadSizeExceededException e) {
        String limit = e.getMaxUploadSize() > 0 ? " of " + e.getMaxUploadSize() + " bytes" : "";
        return error(HttpStatus.PAYLOAD_TOO_LARGE, EngineErrorCode.FILE_TOO_LARGE,
            "The file is larger than the upload limit" + limit + ".");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<EngineError> typeMismatch(MethodArgumentTypeMismatchException e) {
        return invalid("Invalid value '" + e.getValue() + "' for parameter '" + e.getName() + "'.");
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<EngineError> constraintViolated(HandlerMethodValidationException e) {
        String detail = e.getParameterValidationResults().stream()
            .map(result -> result.getMethodParameter().getParameterName() + ": " + result.getResolvableErrors()
                .stream().map(MessageSourceResolvable::getDefaultMessage).collect(Collectors.joining(", ")))
            .collect(Collectors.joining("; "));
        return invalid("Invalid request parameter: " + detail + ".");
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<EngineError> notMultipart(MultipartException e) {
        return invalid("The request is not a valid multipart upload: " + e.getMessage());
    }

    /** Spring's own request errors, e.g. a missing part (400) or an unsupported method (405). */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<EngineError> unexpected(Exception e) {
        if (e instanceof ErrorResponse response && response.getStatusCode().is4xxClientError()) {
            return error(response.getStatusCode(), EngineErrorCode.INVALID_REQUEST, e.getMessage());
        }
        log.error("Engine request failed", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, EngineErrorCode.INTERNAL,
            "The engine could not complete the request because of an unexpected error.");
    }

    private static ResponseEntity<EngineError> invalid(String message) {
        return error(HttpStatus.BAD_REQUEST, EngineErrorCode.INVALID_REQUEST, message);
    }

    private static ResponseEntity<EngineError> error(HttpStatusCode status, EngineErrorCode code, String message) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
            .body(new EngineError(code, message));
    }
}
