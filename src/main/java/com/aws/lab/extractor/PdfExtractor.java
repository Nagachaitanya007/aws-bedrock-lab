package com.aws.lab.extractor;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.util.ArrayList;
import java.util.List;

public class PdfExtractor implements DocumentExtractor {

    @Override
    public boolean supports(String format) {
        return "PDF".equalsIgnoreCase(format);
    }

    @Override
    public List<ExtractedChunk> extract(byte[] rawBytes, String filename) throws Exception {
        List<ExtractedChunk> chunks = new ArrayList<>();

        try (PDDocument document = Loader.loadPDF(rawBytes)) {
            if (document.isEncrypted()) {
                throw new IllegalStateException("Encrypted / password-protected PDF cannot be ingested without credentials");
            }

            int totalPages = document.getNumberOfPages();
            PDFTextStripper stripper = new PDFTextStripper();

            for (int page = 1; page <= totalPages; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);

                String pageText = stripper.getText(document).trim();
                String sectionTitle = "Page " + page;

                if (!pageText.isEmpty()) {
                    chunks.addAll(TokenChunker.chunkText(sectionTitle, pageText));
                } else {
                    // Empty or scanned page
                    chunks.add(new ExtractedChunk(sectionTitle, "[Visual/Scanned content on page " + page + " - textual content absent]"));
                }
            }
        }

        return chunks;
    }
}
