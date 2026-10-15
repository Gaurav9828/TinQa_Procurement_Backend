package com.tinqa.procurement.document.service.impl;

import com.tinqa.procurement.common.exception.ApiException;
import com.tinqa.procurement.common.exception.ResourceNotFoundException;
import com.tinqa.procurement.document.config.FileStorageProperties;
import com.tinqa.procurement.document.service.FileStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.UUID;

/**
 * Stores files on the local disk under a random name, so client-supplied names never become paths.
 */
@Slf4j
@Service
public class LocalFileStorageService implements FileStorageService {

    private final Path fileStorageLocation;

    public LocalFileStorageService(FileStorageProperties properties) {
        this.fileStorageLocation = Paths.get(properties.uploadDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.fileStorageLocation);
        } catch (IOException exception) {
            throw new ApiException("Could not create execution directory for uploaded files.", exception);
        }
    }

    @Override
    public String storeFile(MultipartFile file, String pathPrefix) {
        String originalFileName = StringUtils.cleanPath(file.getOriginalFilename());
        if (originalFileName.contains("..")) {
            throw new ApiException("Filename contains invalid path sequence " + originalFileName);
        }

        int dot = originalFileName.lastIndexOf('.');
        String extension = dot >= 0 ? originalFileName.substring(dot).toLowerCase(Locale.ROOT) : "";
        String newFileName = UUID.randomUUID() + extension;
        try (InputStream content = file.getInputStream()) {
            Path targetLocation = resolve(pathPrefix).resolve(newFileName);
            Files.createDirectories(targetLocation.getParent());
            Files.copy(content, targetLocation, StandardCopyOption.REPLACE_EXISTING);
            return pathPrefix + "/" + newFileName;
        } catch (IOException exception) {
            throw new ApiException("Could not store file " + originalFileName + ". Please try again!", exception);
        }
    }

    @Override
    public Resource loadFileAsResource(String storageKey) {
        try {
            Resource resource = new UrlResource(resolve(storageKey).toUri());
            if (!resource.exists()) {
                throw new ResourceNotFoundException("File not found " + storageKey);
            }
            return resource;
        } catch (MalformedURLException exception) {
            throw new ResourceNotFoundException("File not found " + storageKey);
        }
    }

    @Override
    public void deleteFile(String storageKey) {
        try {
            Files.deleteIfExists(resolve(storageKey));
        } catch (IOException exception) {
            // The document record is already gone; an orphaned file is only wasted space
            log.warn("Could not delete stored file {}", storageKey, exception);
        }
    }

    // Keys are resolved inside the storage directory only
    private Path resolve(String storageKey) {
        Path path = fileStorageLocation.resolve(storageKey).normalize();
        if (!path.startsWith(fileStorageLocation)) {
            throw new ResourceNotFoundException("File not found " + storageKey);
        }
        return path;
    }
}
