package com.tinqa.procurement.document.dto;

import org.springframework.core.io.Resource;

/**
 * A stored file with the metadata needed to serve it.
 */
public record DocumentDownload(Resource resource, String contentType, String fileName) {
}
