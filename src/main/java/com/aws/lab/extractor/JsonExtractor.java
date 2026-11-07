package com.aws.lab.extractor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class JsonExtractor implements DocumentExtractor {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public boolean supports(String format) {
        return "JSON".equalsIgnoreCase(format) || "LOG".equalsIgnoreCase(format);
    }

    @Override
    public List<ExtractedChunk> extract(byte[] rawBytes, String filename) throws Exception {
        List<ExtractedChunk> chunks = new ArrayList<>();
        JsonNode root = mapper.readTree(rawBytes);

        if (root.isArray()) {
            int index = 1;
            for (JsonNode item : root) {
                String pretty = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(item);
                chunks.addAll(TokenChunker.chunkText("Record " + index++, pretty));
            }
        } else if (root.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String key = entry.getKey();
                JsonNode value = entry.getValue();

                String formattedValue = value.isContainerNode()
                        ? mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value)
                        : value.asText();

                chunks.addAll(TokenChunker.chunkText("Property: " + key, formattedValue));
            }
        } else {
            chunks.add(new ExtractedChunk("JSON Value", root.asText()));
        }

        if (chunks.isEmpty()) {
            chunks.add(new ExtractedChunk("JSON Document", "{}"));
        }

        return chunks;
    }
}
