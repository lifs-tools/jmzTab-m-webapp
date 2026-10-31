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
package org.lifstools.mztab.validator.webapp.service;

import org.lifstools.mztab.validator.webapp.domain.UserSessionFile;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

/**
 *
 * @author Nils Hoffmann nils.hoffmann@cebitec.uni-bielefeld.de;
 */
public interface StorageService {
    
    public static enum SLOT {MZTABFILE, MAPPINGFILE, RECORD, SECTIONS, WORKINGCOPY};

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

    /**
     * Deletes all files of one slot of a session; nothing happens if the slot
     * holds no files.
     */
    void delete(UUID sessionId, SLOT slot);

    /**
     * Stores {@code content} as {@code name} in the slot of a session,
     * replacing an existing file atomically: readers see either the old or the
     * new content, never a partial file.
     *
     * @throws IllegalArgumentException if {@code name} does not match
     * {@code [A-Za-z0-9._-]{1,64}} or is {@code .} or {@code ..}
     */
    void write(UUID sessionId, SLOT slot, String name, InputStream content);

    /**
     * Opens a stored file; the caller closes the stream.
     *
     * @return empty if the file does not exist
     * @throws IllegalArgumentException for an unsafe name, as in {@link #write}
     */
    Optional<InputStream> read(UUID sessionId, SLOT slot, String name);

    /**
     * The path of a file in the slot of a session, for code that needs a real
     * file (a database). Creates the slot directory; the file itself is not
     * created.
     *
     * @throws IllegalArgumentException for an unsafe name, as in {@link #write}
     */
    Path localFile(UUID sessionId, SLOT slot, String name);

    /** Sets the modification time of the session directory to now, which restarts its retention. */
    void touch(UUID sessionId);

    /** The ids of all sessions currently in storage. */
    Stream<UUID> sessions();

    /** The total size in bytes of all stored files. */
    long totalBytes();

}
