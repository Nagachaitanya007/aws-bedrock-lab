package com.aws.lab.extractor;

import java.util.List;

public class ExtractorFactory {

    private static final List<DocumentExtractor> EXTRACTORS = List.of(
            new PdfExtractor(),
            new DocxExtractor(),
            new XlsxExtractor(),
            new JsonExtractor(),
            new MarkdownAndTextExtractor() // Fallback
    );

    public static DocumentExtractor getExtractor(String format, String filename) {
        String effectiveFormat = format;

        if (effectiveFormat == null || effectiveFormat.isBlank() || "UNKNOWN".equalsIgnoreCase(effectiveFormat)) {
            effectiveFormat = detectFormatFromFilename(filename);
        }

        for (DocumentExtractor extractor : EXTRACTORS) {
            if (extractor.supports(effectiveFormat)) {
                return extractor;
            }
        }

        return new MarkdownAndTextExtractor();
    }

    private static String detectFormatFromFilename(String filename) {
        if (filename == null) return "TXT";
        int dot = filename.lastIndexOf('.');
        if (dot != -1 && dot < filename.length() - 1) {
            return filename.substring(dot + 1).toUpperCase();
        }
        return "TXT";
    }
}
