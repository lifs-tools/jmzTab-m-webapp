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
package org.lifstools.mztab2.server.api;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import org.lifstools.mztab.validator.webapp.service.CappedInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Request bodies of the endpoints that validate or convert a file, up to the
 * upload limit of the validation form. A body that declares a larger length is
 * refused before it is read; a body without a declared length is counted as it
 * is read. Either way {@link MaxUploadSizeExceededException} answers 413.
 */
@Component
public class RequestBodies {

    private final long maxBytes;

    public RequestBodies(@Value("${spring.servlet.multipart.max-file-size:64MB}") DataSize maxUpload) {
        this.maxBytes = maxUpload.toBytes();
    }

    /**
     * The body of {@code request}; reading more than the upload limit from it
     * throws {@link MaxUploadSizeExceededException}.
     *
     * @throws MaxUploadSizeExceededException if the request declares a larger
     * body
     */
    public InputStream open(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > maxBytes) {
            throw new MaxUploadSizeExceededException(maxBytes);
        }
        return limit(request.getInputStream(), maxBytes);
    }

    /** {@code in}, throwing {@link MaxUploadSizeExceededException} once more than {@code maxBytes} are read. */
    static InputStream limit(InputStream in, long maxBytes) {
        return new CappedInputStream(in, maxBytes, () -> new MaxUploadSizeExceededException(maxBytes));
    }
}
