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
package org.lifstools.mztab.validator.webapp.service.storage;

import org.lifstools.mztab.validator.webapp.domain.UserSessionFile;
import org.lifstools.mztab.validator.webapp.service.StorageService;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 *
 * @author Nils Hoffmann nils.hoffmann@cebitec.uni-bielefeld.de;
 */
@Slf4j
@Service
public class FileSystemStorageService implements StorageService {

    private final Path rootLocation;

    @Value("${minCleanupAge}")
    private Long minCleanupAge = 7l;

    @Autowired
    public FileSystemStorageService(StorageProperties properties) {
        this.rootLocation = Paths.get(properties.getLocation());
    }

    public void setMinCleanupAge(long days) {
        this.minCleanupAge = days;
    }

    public long getMinCleanupAge() {
        return this.minCleanupAge;
    }

    @Override
    public UserSessionFile store(MultipartFile file, String userFileName,
        UUID sessionId, SLOT slot) {
        String filename = StringUtils.cleanPath(userFileName);
        try {
            if (file.isEmpty()) {
                throw new StorageException(
                    "Failed to store empty file " + filename);
            }

            Path sessionPath = buildSessionPath(sessionId);
            Path filePath = buildPathToFile(sessionPath,
                filename, slot);
            Files.createDirectories(filePath.getParent());
            Files.copy(file.getInputStream(), filePath,
                StandardCopyOption.REPLACE_EXISTING);
            return new UserSessionFile(filename, sessionId);
        } catch (IOException e) {
            throw new StorageException("Failed to store file " + filename, e);
        }
    }

    @Override
    public UserSessionFile store(URL url, UUID sessionId, SLOT slot) {
        try {
            String fileContent = getResourceFileAsString(url);
            return store(fileContent, sessionId, slot);
        } catch (IOException ioex) {
            throw new StorageException("Failed to store file " + url, ioex);
        }
    }

    private static String getResourceFileAsString(URL url) throws IOException {
        try (InputStream is = url.openStream()) {
            if (is != null) {
                try (
                    BufferedReader reader = new BufferedReader(
                        new InputStreamReader(is))) {
                    return reader.lines().
                        collect(Collectors.joining(System.lineSeparator()));
                }
            }
        }
        return null;
    }

    @Override
    public UserSessionFile store(MultipartFile file, UUID sessionId, SLOT slot) {
        return store(file, file.getOriginalFilename(), sessionId, slot);
    }

    @Override
    public UserSessionFile store(String fileContent, UUID sessionId, SLOT slot) {
        String ext;
        switch (slot) {
            case MAPPINGFILE:
                ext = ".xml";
                break;
            case MZTABFILE:
                ext = ".mztab";
                break;
            default:
                throw new StorageException("Unknown file slot " + slot);

        }
        String filename = UUID.randomUUID() + ext;
        try {
            Path sessionPath = buildSessionPath(sessionId);
            Path filePath = buildPathToFile(sessionPath, filename, slot);
            Files.createDirectories(filePath.getParent());
            Files.write(filePath, fileContent.
                getBytes("UTF-8"), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
            return new UserSessionFile(filename, sessionId);
        } catch (IOException e) {
            throw new StorageException("Failed to store file " + filename, e);
        }
    }

    @Override
    public UserSessionFile load(UUID sessionId, SLOT slot) {
        Path sessionPath = buildSessionPath(sessionId).resolve(slot.name());
        try {
            Path mzTabFile = Files.walk(sessionPath, 1).
                filter(path ->
                    !path.equals(sessionPath)).
                map(path ->
                    sessionPath.relativize(path)).
                findFirst().
                get();
            return new UserSessionFile(mzTabFile.
                toString(), sessionId);
        } catch (IOException ex) {
            throw new StorageException("Failed to read stored files", ex);
        }
    }

    @Override
    public Stream<Path> loadAll(UUID sessionId) {
        Path sessionPath = buildSessionPath(sessionId);
        try {
            return Files.walk(sessionPath, 1).
                filter(path ->
                    !path.equals(sessionPath)).
                map(path ->
                    sessionPath.relativize(path));
        } catch (IOException e) {
            throw new StorageException("Failed to read stored files", e);
        }
    }

    @Scheduled(cron = "${oldFileMaintenanceSchedule}")
    public void deleteOldFiles() {
        log.info("Starting old file maintenance");
        Clock clock = Clock.systemUTC();
        Instant now = clock.instant(); // Where clock is a java.time.Clock, for testability
        log.info("Reference instant is {}", now);
        try {
            Files.newDirectoryStream(this.rootLocation, path ->
                path.toFile().
                    isDirectory()).
                forEach((dir) ->
                {
                    try {
                        log.info("Checking directory {}", dir);
                        FileTime t = Files.getLastModifiedTime(dir);
                        Instant fileInstant = t.toInstant();
                        Duration difference = Duration.between(fileInstant, now);
                        long days = difference.toDays();
                        log.info("Age difference for {} is {} days", dir, days);
                        if (days >= minCleanupAge) {
                            log.info("Deleting {}", dir);
                            FileSystemUtils.deleteRecursively(dir.toFile());
                        } else {
                            log.info("Not deleting {}", dir);
                        }
                    } catch (IOException ex) {
                        log.error(
                            "Encountered exception during old file maintenance:",
                            ex);
                    }

                });
        } catch (IOException ioex) {
            log.
                error("Encountered exception during old file maintenance:", ioex);
        }
        log.info("Stopping old file maintenance");
    }

    private Path buildSessionPath(UUID sessionId) {
        if (sessionId == null) {
            throw new StorageException(
                "Cannot store file when sessionId is null!");
        }
        return this.rootLocation.resolve(sessionId.toString());
    }

    private Path buildPathToFile(Path sessionPath, String filename, SLOT slot) {
        if (filename.contains("..")) {
            // This is a security check
            throw new StorageException(
                "Cannot store file with relative path outside current directory "
                + filename);
        }
        return sessionPath.resolve(slot.name()).
            resolve(filename);
    }

    @Override
    public Path load(UserSessionFile userSessionFile, SLOT slot) {
        Path p = buildSessionPath(userSessionFile.getSessionId());
        return buildPathToFile(p, userSessionFile.getFilename(), slot);
//        return p.resolve(userSessionFile.getFilename());
    }

    @Override
    public Resource loadAsResource(UserSessionFile userSessionFile, SLOT slot) {
        if (userSessionFile == null) {
            throw new StorageException(
                "Cannot retrieve file when userSessionFile is null!");
        }
        try {
            Path file = load(userSessionFile, slot);
            Resource resource = new UrlResource(file.toUri());
            if (resource.exists() || resource.isReadable()) {
                return resource;
            } else {
                throw new StorageFileNotFoundException(
                    "Could not read file: " + userSessionFile.getFilename());

            }
        } catch (MalformedURLException e) {
            throw new StorageFileNotFoundException(
                "Could not read file: " + userSessionFile.getFilename(), e);
        }
    }

    @Override
    public void deleteAll(UUID sessionId) {
        Path sessionPath = buildSessionPath(sessionId);
        FileSystemUtils.deleteRecursively(sessionPath.toFile());
    }

    @Override
    public void deleteAll() {
        FileSystemUtils.deleteRecursively(this.rootLocation.toFile());
    }

    @Override
    public void init() {
        try {
            Files.createDirectories(rootLocation);
        } catch (IOException e) {
            throw new StorageException("Could not initialize storage", e);
        }
    }
}
