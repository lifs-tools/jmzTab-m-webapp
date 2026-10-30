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
package org.lifstools.mztab.validator.webapp.session;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.lifstools.mztab.validator.engine.api.MzTabVersions;
import org.lifstools.mztab.validator.webapp.engine.EngineMessages;
import org.lifstools.mztab.validator.webapp.engine.EngineRegistry;
import org.lifstools.mztab.validator.webapp.engine.EngineRegistry.RouteDecision;
import org.lifstools.mztab.validator.webapp.service.CappedInputStream;
import org.lifstools.mztab.validator.webapp.service.StorageService;
import org.lifstools.mztab.validator.webapp.service.StorageService.SLOT;
import org.lifstools.mztab.validator.webapp.service.storage.StorageProperties;
import org.lifstools.mztab.validator.webapp.service.validation.SafeCvMappingLoader;
import org.lifstools.mztab.validator.webapp.validationprofile.ValidationProfile;
import org.lifstools.mztab.validator.webapp.validationprofile.ValidationProfileCatalog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

/**
 * Creates, finds and deletes validation sessions. A session is a storage
 * directory holding the upload ({@code MZTABFILE/upload.mztab}), an optional
 * custom mapping ({@code MAPPINGFILE/custom-mapping.xml}) and the persisted
 * {@link ValidationRecord}.
 */
@Service
public class ValidationSessionService {

    static final String UPLOAD = "upload.mztab";
    static final String CUSTOM_MAPPING = "custom-mapping.xml";
    static final int MAX_MAPPING_BYTES = 5 * 1024 * 1024;

    private final StorageService storage;
    private final StorageProperties storageProperties;
    private final ValidationProfileCatalog catalog;
    private final ValidationRecordStore records;
    private final ValidationRunner runner;
    private final EngineRegistry registry;
    private final long retentionDays;
    private final Object capacityLock = new Object();

    public ValidationSessionService(StorageService storage, StorageProperties storageProperties,
        ValidationProfileCatalog catalog, ValidationRecordStore records, ValidationRunner runner,
        EngineRegistry registry, @Value("${minCleanupAge:7}") long retentionDays) {
        this.storage = storage;
        this.storageProperties = storageProperties;
        this.catalog = catalog;
        this.records = records;
        this.runner = runner;
        this.registry = registry;
        this.retentionDays = retentionDays;
    }

    /**
     * Stores a new validation and starts it asynchronously.
     *
     * @param fileName the original file name, recorded as given
     * @param content the mzTab-M file
     * @param options level, message limit and catalog profile
     * @param customMapping a CV mapping file uploaded instead of a catalog profile
     * @return the {@link ValidationStatus#QUEUED} record
     * @throws IllegalArgumentException for an unknown profile, a profile
     * together with a custom mapping, a custom mapping that is larger than
     * 5 MB or rejected by {@link SafeCvMappingLoader}, or a catalog profile
     * whose versions are not of the line the file is validated as
     * @throws StorageFullException if the storage cap is reached or the
     * upload and mapping would exceed it
     */
    public ValidationRecord submit(String fileName, InputStream content, ValidationOptions options,
        @Nullable InputStream customMapping) {
        ValidationProfile catalogProfile = catalogProfile(options, customMapping != null);
        ProfileRef profile = catalogProfile != null ? ProfileRef.catalog(catalogProfile.id(), catalogProfile.version())
            : customMapping != null ? ProfileRef.custom() : ProfileRef.none();
        byte[] mapping = customMapping == null ? null : readMapping(customMapping);

        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        ValidationRecord record = ValidationRecord.queued(id, fileName, options.level(), options.maxErrors(),
            profile, now, now.plus(Duration.ofDays(retentionDays)));
        // check and allocate under one lock, so concurrent submits cannot overshoot the cap together
        synchronized (capacityLock) {
            long remaining = storageProperties.getMaxTotalBytes() - storage.totalBytes();
            if (remaining <= 0) {
                throw new StorageFullException();
            }
            try {
                CappedInputStream upload = new CappedInputStream(content, remaining, StorageFullException::new);
                storage.write(id, SLOT.MZTABFILE, UPLOAD, upload);
                if (mapping != null) {
                    if (mapping.length > remaining - upload.count()) {
                        throw new StorageFullException();
                    }
                    storage.write(id, SLOT.MAPPINGFILE, CUSTOM_MAPPING, new ByteArrayInputStream(mapping));
                }
            } catch (RuntimeException e) {
                storage.deleteAll(id);
                throw e;
            }
        }
        // outside the lock: detection reads the whole upload
        try {
            if (catalogProfile != null) {
                checkLine(id, catalogProfile);
            }
            records.write(record);
        } catch (RuntimeException e) {
            storage.deleteAll(id);
            throw e;
        }
        runner.run(id);
        return record;
    }

    /**
     * A catalog profile applies to the lines of its {@code mzTabVersions}; the
     * file must be validated as one of them. Legacy and unsupported files are
     * left to the runner, which rejects them with their message.
     */
    private void checkLine(UUID id, ValidationProfile profile) {
        Set<String> profileLines = new LinkedHashSet<>();
        profile.mzTabVersions().forEach(v -> MzTabVersions.lineOf(v).ifPresent(profileLines::add));
        if (profileLines.isEmpty()) {
            return;
        }
        Optional<String> declared;
        Path copy = null;
        try {
            copy = ValidationRunner.toTempFile(storage.read(id, SLOT.MZTABFILE, UPLOAD).orElseThrow(
                () -> new IllegalStateException("The upload of validation " + id + " is missing")), ".mztab");
            declared = ValidationRunner.declaredVersion(copy);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the upload of validation " + id, e);
        } finally {
            ValidationRunner.deleteQuietly(copy);
        }
        if (registry.route(declared) instanceof RouteDecision.Routed routed
            && !profileLines.contains(routed.line())) {
            throw new IllegalArgumentException(EngineMessages.profileOfOtherLine(profile.id(), profile.version(),
                profileLines, routed.line()));
        }
    }

    public Optional<ValidationRecord> find(UUID id) {
        return records.read(id);
    }

    /**
     * Removes the upload, the mapping, the record and the section data.
     *
     * @return false if there is no such validation
     */
    public boolean delete(UUID id) {
        return records.delete(id);
    }

    /** The catalog profile of the options, or {@code null} for none or a custom mapping. */
    @Nullable
    private ValidationProfile catalogProfile(ValidationOptions options, boolean customMapping) {
        String id = options.validationProfileId();
        String version = options.validationProfileVersion();
        if (id == null) {
            if (version != null) {
                throw new IllegalArgumentException("A validation profile version needs a validation profile id.");
            }
            return null;
        }
        if (customMapping) {
            throw new IllegalArgumentException(
                "Choose either a validation profile or a custom mapping file, not both.");
        }
        return catalog.find(id, version).orElseThrow(() -> new IllegalArgumentException(
            "Unknown validation profile: " + id + (version == null ? "" : " " + version)));
    }

    private static byte[] readMapping(InputStream in) {
        byte[] bytes;
        try {
            bytes = in.readNBytes(MAX_MAPPING_BYTES + 1);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the mapping file", e);
        }
        if (bytes.length > MAX_MAPPING_BYTES) {
            throw new IllegalArgumentException("The mapping file is larger than 5 MB.");
        }
        SafeCvMappingLoader.validateAgainstSchema(bytes);
        return bytes;
    }
}
