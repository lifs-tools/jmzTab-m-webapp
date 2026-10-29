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
package org.lifstools.mztab.validator.webapp.service.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * A body that cannot be read, e.g. one over the upload limit, leaves no
 * temporary file behind.
 */
class MzTabConverterTest {

    @TempDir
    Path tmp;

    @Test
    void receiveKeepsTheBody() throws Exception {
        Path file = MzTabConverter.receive(new ByteArrayInputStream(new byte[1024]), tmp);
        assertThat(file).hasSize(1024);
    }

    @Test
    void receiveDeletesWhatItReadOfABodyThatFails() throws Exception {
        InputStream failing = new SequenceInputStream(new ByteArrayInputStream(new byte[8192]),
            new InputStream() {
                @Override
                public int read() {
                    throw new MaxUploadSizeExceededException(8192);
                }
            });
        assertThatThrownBy(() -> MzTabConverter.receive(failing, tmp))
            .isInstanceOf(MaxUploadSizeExceededException.class);
        try (Stream<Path> left = Files.list(tmp)) {
            assertThat(left).isEmpty();
        }
    }
}
