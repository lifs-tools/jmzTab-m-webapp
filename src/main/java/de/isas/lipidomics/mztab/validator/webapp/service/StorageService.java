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
package de.isas.lipidomics.mztab.validator.webapp.service;

import de.isas.lipidomics.mztab.validator.webapp.domain.UserSessionFile;
import java.net.URL;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

/**
 *
 * @author Nils Hoffmann &lt;nils.hoffmann@isas.de&gt;
 */
public interface StorageService {
    
    public static enum SLOT {MZTABFILE, MAPPINGFILE};

    void init();

    UserSessionFile store(MultipartFile file, UUID sessionId, SLOT slot);

    UserSessionFile store(MultipartFile file, String userFileName,
        UUID sessionId, SLOT slot);
    
    UserSessionFile store(URL url, UUID sessionId, SLOT slot);

    UserSessionFile store(String fileContent, UUID sessionId, SLOT slot);

    Stream<Path> loadAll(UUID sessionId);
    
    UserSessionFile load(UUID sessionId, SLOT slot);

    Path load(UserSessionFile userSessionFile, SLOT slot);

    Resource loadAsResource(UserSessionFile userSessionFile, SLOT slot);

    void deleteAll(UUID sessionId);

    void deleteAll();

}
