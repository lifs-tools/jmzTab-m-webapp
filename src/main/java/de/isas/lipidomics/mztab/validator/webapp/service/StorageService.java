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

    void init();

    UserSessionFile store(MultipartFile file, UUID sessionId);
    
    UserSessionFile store(String fileContent, UUID sessionId);

    Stream<Path> loadAll(UUID sessionId);

    Path load(UserSessionFile userSessionFile);

    Resource loadAsResource(UserSessionFile userSessionFile);

    void deleteAll(UUID sessionId);
    
    void deleteAll();

}
