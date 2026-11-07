package com.aws.lab.extractor;

import java.util.ArrayList;
import java.util.List;

public class TokenChunker {

    private static final int MAX_WORDS_PER_CHUNK = 400; // ~500-600 tokens
    private static final int OVERLAP_WORDS = 40;

    public static List<ExtractedChunk> chunkText(String sectionTitle, String rawText) {
        List<ExtractedChunk> chunks = new ArrayList<>();
        String cleaned = rawText.trim();
        if (cleaned.isEmpty()) {
            return chunks;
        }

        String[] words = cleaned.split("\\s+");
        if (words.length <= MAX_WORDS_PER_CHUNK) {
            chunks.add(new ExtractedChunk(sectionTitle, cleaned));
            return chunks;
        }

        int start = 0;
        int subPart = 1;
        while (start < words.length) {
            int end = Math.min(start + MAX_WORDS_PER_CHUNK, words.length);
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < end; i++) {
                sb.append(words[i]).append(" ");
            }
            String title = sectionTitle + (words.length > MAX_WORDS_PER_CHUNK ? " (Part " + subPart++ + ")" : "");
            chunks.add(new ExtractedChunk(title, sb.toString().trim()));

            if (end == words.length) {
                break;
            }
            start += (MAX_WORDS_PER_CHUNK - OVERLAP_WORDS);
        }

        return chunks;
    }
}
