package com.tinqa.procurement.common.validation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonMappingException;

/**
 * A JSON string value failed {@link InputSafety}; Jackson adds the field path as it unwinds.
 */
public class UnsafeInputException extends JsonMappingException {

    private final String reason;

    public UnsafeInputException(JsonParser parser, String reason) {
        super(parser, "Unsafe input: " + reason);
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }

    // e.g. "lines[0].reason"
    public String getFieldPath() {
        StringBuilder path = new StringBuilder();
        for (Reference reference : getPath()) {
            if (reference.getFieldName() != null) {
                if (!path.isEmpty()) {
                    path.append('.');
                }
                path.append(reference.getFieldName());
            } else if (reference.getIndex() >= 0) {
                path.append('[').append(reference.getIndex()).append(']');
            }
        }
        return path.isEmpty() ? "body" : path.toString();
    }
}
