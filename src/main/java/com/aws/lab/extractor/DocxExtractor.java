package com.aws.lab.extractor;

import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

public class DocxExtractor implements DocumentExtractor {

    @Override
    public boolean supports(String format) {
        return "DOCX".equalsIgnoreCase(format) || "DOC".equalsIgnoreCase(format);
    }

    @Override
    public List<ExtractedChunk> extract(byte[] rawBytes, String filename) throws Exception {
        List<ExtractedChunk> chunks = new ArrayList<>();

        try (ByteArrayInputStream bais = new ByteArrayInputStream(rawBytes);
             XWPFDocument document = new XWPFDocument(bais)) {

            String currentSection = "Document Overview";
            StringBuilder currentContent = new StringBuilder();

            for (IBodyElement element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    String text = paragraph.getText().trim();
                    if (text.isEmpty()) continue;

                    String style = paragraph.getStyle();
                    boolean isHeading = (style != null && style.toLowerCase().contains("heading"))
                            || text.startsWith("#");

                    if (isHeading) {
                        // Flush previous section
                        if (!currentContent.isEmpty()) {
                            chunks.addAll(TokenChunker.chunkText(currentSection, currentContent.toString()));
                            currentContent.setLength(0);
                        }
                        currentSection = text.replaceFirst("^#+\\s*", "");
                    } else {
                        currentContent.append(text).append("\n\n");
                    }
                } else if (element instanceof XWPFTable table) {
                    // Convert Word table to Markdown Table
                    String mdTable = formatTableToMarkdown(table);
                    if (!mdTable.isBlank()) {
                        currentContent.append("\n").append(mdTable).append("\n\n");
                    }
                }
            }

            // Flush final section
            if (!currentContent.isEmpty()) {
                chunks.addAll(TokenChunker.chunkText(currentSection, currentContent.toString()));
            }
        }

        if (chunks.isEmpty()) {
            chunks.add(new ExtractedChunk("Document Overview", "[Empty Word Document]"));
        }

        return chunks;
    }

    private String formatTableToMarkdown(XWPFTable table) {
        List<XWPFTableRow> rows = table.getRows();
        if (rows.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < rows.size(); r++) {
            XWPFTableRow row = rows.get(r);
            sb.append("|");
            for (XWPFTableCell cell : row.getTableCells()) {
                String cellText = cell.getText().replace("\n", " ").trim();
                sb.append(" ").append(cellText).append(" |");
            }
            sb.append("\n");

            // Header separator after first row
            if (r == 0) {
                sb.append("|");
                for (int c = 0; c < row.getTableCells().size(); c++) {
                    sb.append(" --- |");
                }
                sb.append("\n");
            }
        }
        return sb.toString();
    }
}
