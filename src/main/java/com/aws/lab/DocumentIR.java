package com.aws.lab.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DocumentIR(
        String documentId,
        String documentTitle,
        String documentType,
        String sourceFormat,
        Map<String, Object> metadata,
        List<Element> elements
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Element(
            ElementType type,
            String text,
            Map<String, Object> elementMetadata
    ) {}

    public enum ElementType {
        HEADING,
        PARAGRAPH,
        CODE_BLOCK,
        TABLE,
        LIST_ITEM
    }
}