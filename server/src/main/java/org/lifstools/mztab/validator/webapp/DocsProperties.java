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
package org.lifstools.mztab.validator.webapp;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.lang.Nullable;
import org.springframework.validation.annotation.Validated;

/**
 * Where the documentation points users to ({@code docs.submission-template-url} /
 * {@code docs.submission-template-example-url}; environment {@code DOCS_SUBMISSION_TEMPLATE_URL} /
 * {@code DOCS_SUBMISSION_TEMPLATE_EXAMPLE_URL}). A blank value means none; otherwise the value must be an absolute
 * https URL of at most 2048 characters, or startup fails.
 *
 * @param submissionTemplateUrl the LipidCompass submission template (Excel)
 * @param submissionTemplateExampleUrl the filled-in example of the template
 */
@Validated
@ConfigurationProperties("docs")
public record DocsProperties(@Nullable String submissionTemplateUrl, @Nullable String submissionTemplateExampleUrl) {

    private static final int MAX_LENGTH = 2048;

    public DocsProperties {
        submissionTemplateUrl = checked("docs.submission-template-url", submissionTemplateUrl);
        submissionTemplateExampleUrl = checked("docs.submission-template-example-url", submissionTemplateExampleUrl);
    }

    @Nullable
    private static String checked(String property, @Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String url = value.strip();
        if (url.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(property + " must be at most " + MAX_LENGTH + " characters");
        }
        try {
            URI uri = URI.create(url);
            if (uri.getHost() != null && "https".equalsIgnoreCase(uri.getScheme())) {
                return url;
            }
        } catch (IllegalArgumentException e) {
            // reported below
        }
        throw new IllegalArgumentException(property + " must be blank or an absolute https URL");
    }
}
