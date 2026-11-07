package com.aws.lab.extractor;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class MarkdownAndTextExtractor implements DocumentExtractor {

    @Override
    public boolean supports(String format) {
        return "MD".equalsIgnoreCase(format)
                || "MARKDOWN".equalsIgnoreCase(format)
                || "TXT".equalsIgnoreCase(format)
                || "TEXT".equalsIgnoreCase(format)
                || "LOG".equalsIgnoreCase(format)
                || "UNKNOWN".equalsIgnoreCase(format);
    }

    @Override
    public List<ExtractedChunk> extract(byte[] rawBytes, String filename) {
        List<ExtractedChunk> chunks = new ArrayList<>();
        String text = new String(rawBytes, StandardCharsets.UTF_8).trim();

        if (text.isEmpty()) {
            chunks.add(new ExtractedChunk("Overview", "[Empty Document]"));
            return chunks;
        }

        // Split by markdown headers if present
        if (text.contains("# ") || text.contains("## ") || text.contains("### ")) {
            String[] sections = text.split("(?=(?m)^#{1,3}\\s+)");
            for (String section : sections) {
                String trimmed = section.trim();
                if (trimmed.isEmpty()) continue;

                String sectionTitle = "Overview";
                int firstLineEnd = trimmed.indexOf('\n');
                String firstLine = firstLineEnd != -1 ? trimmed.substring(0, firstLineEnd).trim() : trimmed;

                if (firstLine.startsWith("#")) {
                    sectionTitle = firstLine.replaceFirst("^#+\\s*", "");
                }

                chunks.addAll(TokenChunker.chunkText(sectionTitle, trimmed));
            }
        } else {
            // Plain text: split into paragraphs and chunk
            chunks.addAll(TokenChunker.chunkText("Overview", text));
        }

        if (chunks.isEmpty()) {
            chunks.add(new ExtractedChunk("Overview", text));
        }

        return chunks;
    }
}
