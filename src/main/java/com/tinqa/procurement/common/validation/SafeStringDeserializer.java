package com.tinqa.procurement.common.validation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.deser.ContextualDeserializer;
import com.fasterxml.jackson.databind.deser.std.StdScalarDeserializer;
import com.fasterxml.jackson.databind.deser.std.StringDeserializer;

import java.io.IOException;

/**
 * Applies {@link InputSafety} to every JSON string the service reads, including values nested in
 * free-form maps (attributes, additionalInfo, taxBreakup).
 */
public class SafeStringDeserializer extends StdScalarDeserializer<String> implements ContextualDeserializer {

    private final boolean allowLinks;

    public SafeStringDeserializer() {
        this(false);
    }

    private SafeStringDeserializer(boolean allowLinks) {
        super(String.class);
        this.allowLinks = allowLinks;
    }

    @Override
    public JsonDeserializer<?> createContextual(DeserializationContext context, BeanProperty property) {
        if (property == null) {
            return this;
        }
        if (property.getAnnotation(RawInput.class) != null) {
            return StringDeserializer.instance;
        }
        if (property.getAnnotation(UrlInput.class) != null) {
            return new SafeStringDeserializer(true);
        }
        return this;
    }

    @Override
    public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        String value = StringDeserializer.instance.deserialize(parser, context);
        String violation = InputSafety.findViolation(value, allowLinks);
        if (violation != null) {
            throw new UnsafeInputException(parser, violation);
        }
        return value;
    }
}
