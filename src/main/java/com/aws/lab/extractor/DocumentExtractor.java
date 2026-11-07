package com.aws.lab.extractor;

import java.util.List;

public interface DocumentExtractor {

    /**
     * Checks if this extractor supports the given format (e.g. PDF, DOCX, XLSX, JSON, MD, TXT).
     */
    boolean supports(String format);

    /**
     * Extracts text, sections, and tables into intermediate chunks.
     */
    List<ExtractedChunk> extract(byte[] rawBytes, String filename) throws Exception;
}
