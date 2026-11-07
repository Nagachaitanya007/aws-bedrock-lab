package com.aws.lab.extractor;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ExtractorTest {

    @Test
    public void testJsonExtractor() throws Exception {
        String json = """
        {
            "database": {
                "engine": "PostgreSQL",
                "version": "16"
            },
            "caching": {
                "engine": "Redis",
                "port": 6379
            }
        }
        """;

        DocumentExtractor extractor = ExtractorFactory.getExtractor("JSON", "config.json");
        assertTrue(extractor instanceof JsonExtractor);

        List<ExtractedChunk> chunks = extractor.extract(json.getBytes(StandardCharsets.UTF_8), "config.json");
        assertEquals(2, chunks.size());
        assertTrue(chunks.stream().anyMatch(c -> c.sectionTitle().contains("database")));
        assertTrue(chunks.stream().anyMatch(c -> c.sectionTitle().contains("caching")));
    }

    @Test
    public void testMarkdownExtractor() throws Exception {
        String md = """
        # Incident Post-Mortem

        ## Summary
        Service experienced high latency.

        ## Remediation
        Scaled up worker pools.
        """;

        DocumentExtractor extractor = ExtractorFactory.getExtractor("MD", "incident.md");
        assertTrue(extractor instanceof MarkdownAndTextExtractor);

        List<ExtractedChunk> chunks = extractor.extract(md.getBytes(StandardCharsets.UTF_8), "incident.md");
        assertFalse(chunks.isEmpty());
        assertTrue(chunks.stream().anyMatch(c -> c.sectionTitle().equalsIgnoreCase("Summary")));
        assertTrue(chunks.stream().anyMatch(c -> c.sectionTitle().equalsIgnoreCase("Remediation")));
    }

    @Test
    public void testPdfExtractor() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText("Architecture Specification: SQS DLQ pattern isolates poison pill messages.");
                cs.endText();
            }
            doc.save(baos);
        }

        DocumentExtractor extractor = ExtractorFactory.getExtractor("PDF", "spec.pdf");
        assertTrue(extractor instanceof PdfExtractor);

        List<ExtractedChunk> chunks = extractor.extract(baos.toByteArray(), "spec.pdf");
        assertEquals(1, chunks.size());
        assertEquals("Page 1", chunks.get(0).sectionTitle());
        assertTrue(chunks.get(0).content().contains("SQS DLQ pattern"));
    }

    @Test
    public void testDocxExtractor() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFParagraph p1 = doc.createParagraph();
            p1.setStyle("Heading 1");
            p1.createRun().setText("Architecture Decision");

            XWPFParagraph p2 = doc.createParagraph();
            p2.createRun().setText("We selected Amazon ElastiCache for session storage.");

            doc.write(baos);
        }

        DocumentExtractor extractor = ExtractorFactory.getExtractor("DOCX", "decision.docx");
        assertTrue(extractor instanceof DocxExtractor);

        List<ExtractedChunk> chunks = extractor.extract(baos.toByteArray(), "decision.docx");
        assertFalse(chunks.isEmpty());
        assertTrue(chunks.get(0).content().contains("Amazon ElastiCache"));
    }

    @Test
    public void testXlsxExtractor() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Services");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("ServiceName");
            header.createCell(1).setCellValue("Port");

            Row row1 = sheet.createRow(1);
            row1.createCell(0).setCellValue("order-service");
            row1.createCell(1).setCellValue("8081");

            wb.write(baos);
        }

        DocumentExtractor extractor = ExtractorFactory.getExtractor("XLSX", "inventory.xlsx");
        assertTrue(extractor instanceof XlsxExtractor);

        List<ExtractedChunk> chunks = extractor.extract(baos.toByteArray(), "inventory.xlsx");
        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).sectionTitle().contains("Services"));
        assertTrue(chunks.get(0).content().contains("| ServiceName | Port |"));
        assertTrue(chunks.get(0).content().contains("order-service"));
    }
}
