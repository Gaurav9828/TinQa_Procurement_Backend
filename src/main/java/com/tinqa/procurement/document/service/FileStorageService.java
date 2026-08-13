package com.tinqa.procurement.document.service;

import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

public interface FileStorageService {
    String storeFile(MultipartFile file, String pathPrefix);
    Resource loadFileAsResource(String storageKey);
    void deleteFile(String storageKey);
}