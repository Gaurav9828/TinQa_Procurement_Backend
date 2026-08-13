package com.tinqa.procurement.document.service.impl;

import com.tinqa.procurement.common.exception.ApiException;
import com.tinqa.procurement.common.exception.ResourceNotFoundException;
import com.tinqa.procurement.document.service.FileStorageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.*;
import java.util.UUID;

@Service
public class LocalFileStorageService implements FileStorageService {

    private final Path fileStorageLocation;

    public LocalFileStorageService(@Value("${file.upload-dir:./uploads}") String uploadDir) {
        this.fileStorageLocation = Paths.get(uploadDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.fileStorageLocation);
        } catch (Exception ex) {
            throw new ApiException("Could not create execution directory for uploaded files.");
        }
    }

    @Override
    public String storeFile(MultipartFile file, String pathPrefix) {
        String originalFileName = StringUtils.cleanPath(file.getOriginalFilename());
        try {
            if (originalFileName.contains("..")) {
                throw new ApiException("Filename contains invalid path sequence " + originalFileName);
            }

            String extension = "";
            int i = originalFileName.lastIndexOf('.');
            if (i >= 0) {
                extension = originalFileName.substring(i);
            }

            String newFileName = UUID.randomUUID() + extension;
            Path targetLocation = this.fileStorageLocation.resolve(pathPrefix).resolve(newFileName);
            Files.createDirectories(targetLocation.getParent());

            Files.copy(file.getInputStream(), targetLocation, StandardCopyOption.REPLACE_EXISTING);
            return pathPrefix + "/" + newFileName;
        } catch (IOException ex) {
            throw new ApiException("Could not store file " + originalFileName + ". Please try again!");
        }
    }

    @Override
    public Resource loadFileAsResource(String storageKey) {
        try {
            Path filePath = this.fileStorageLocation.resolve(storageKey).normalize();
            Resource resource = new UrlResource(filePath.toUri());
            if (resource.exists()) {
                return resource;
            } else {
                throw new ResourceNotFoundException("File not found " + storageKey);
            }
        } catch (MalformedURLException ex) {
            throw new ResourceNotFoundException("File not found " + storageKey);
        }
    }

    @Override
    public void deleteFile(String storageKey) {
        try {
            Path filePath = this.fileStorageLocation.resolve(storageKey).normalize();
            Files.deleteIfExists(filePath);
        } catch (IOException ex) {
            // Soft ignore or log error
        }
    }
}