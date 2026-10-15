package com.tinqa.procurement.document.service;

import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

/**
 * Where document files live. Files are addressed by an opaque storage key returned when they are stored.
 */
public interface FileStorageService {

    /** @return the storage key of the new file */
    String storeFile(MultipartFile file, String pathPrefix);

    Resource loadFileAsResource(String storageKey);

    void deleteFile(String storageKey);
}
