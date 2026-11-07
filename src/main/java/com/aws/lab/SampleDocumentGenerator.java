package com.aws.lab;

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

import java.io.File;
import java.io.FileOutputStream;

public class SampleDocumentGenerator {

    public static void main(String[] args) throws Exception {
        File dir = new File("E:\\aws-bedrock-lab\\documents");
        if (!dir.exists()) dir.mkdirs();

        System.out.println(">>> Generating miniature PDF, Word, and Excel sample files...");

        // 1. Generate network-spec.pdf
        File pdfFile = new File(dir, "network-spec.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 14);
                cs.newLineAtOffset(50, 720);
                cs.showText("Network Specification: Transit Gateway Peering");

                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                cs.newLineAtOffset(0, -30);
                cs.showText("The checkout-vpc (10.0.0.0/16) connects to payment-vpc (10.1.0.0/16)");
                cs.newLineAtOffset(0, -18);
                cs.showText("via an AWS Transit Gateway peering attachment with MTU of 8500 bytes.");
                cs.newLineAtOffset(0, -18);
                cs.showText("Inter-region encryption is enforced using AWS MACsec over direct connect.");
                cs.endText();
            }
            doc.save(pdfFile);
            System.out.println("  [+] Created: " + pdfFile.getAbsolutePath());
        }

        // 2. Generate security-policy.docx
        File docxFile = new File(dir, "security-policy.docx");
        try (XWPFDocument doc = new XWPFDocument();
             FileOutputStream fos = new FileOutputStream(docxFile)) {

            XWPFParagraph h1 = doc.createParagraph();
            h1.setStyle("Heading 1");
            h1.createRun().setText("Encryption Standards");

            XWPFParagraph p1 = doc.createParagraph();
            p1.createRun().setText("All S3 buckets and RDS PostgreSQL databases must enforce AES-256 encryption at rest using AWS KMS Customer Managed Keys (CMK).");

            XWPFParagraph h2 = doc.createParagraph();
            h2.setStyle("Heading 1");
            h2.createRun().setText("Remote Administration Access");

            XWPFParagraph p2 = doc.createParagraph();
            p2.createRun().setText("Direct SSH port 22 access is prohibited in production. All bastion tunnels must authenticate through AWS Systems Manager Session Manager.");

            doc.write(fos);
            System.out.println("  [+] Created: " + docxFile.getAbsolutePath());
        }

        // 3. Generate infrastructure-nodes.xlsx
        File xlsxFile = new File(dir, "infrastructure-nodes.xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook();
             FileOutputStream fos = new FileOutputStream(xlsxFile)) {

            Sheet sheet = wb.createSheet("ComputeNodes");

            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("NodeID");
            header.createCell(1).setCellValue("InstanceType");
            header.createCell(2).setCellValue("ServiceRole");
            header.createCell(3).setCellValue("AvailabilityZone");
            header.createCell(4).setCellValue("MaxConnections");

            String[][] rows = {
                    {"node-kafka-01", "m5.large", "kafka-broker", "us-east-1a", "5000"},
                    {"node-kafka-02", "m5.large", "kafka-broker", "us-east-1b", "5000"},
                    {"node-redis-01", "cache.t4g.micro", "session-cache", "us-east-1c", "1000"},
                    {"node-pg-01", "db.t4g.micro", "pgvector-primary", "us-east-1c", "200"}
            };

            for (int r = 0; r < rows.length; r++) {
                Row row = sheet.createRow(r + 1);
                for (int c = 0; c < rows[r].length; c++) {
                    row.createCell(c).setCellValue(rows[r][c]);
                }
            }

            wb.write(fos);
            System.out.println("  [+] Created: " + xlsxFile.getAbsolutePath());
        }

        System.out.println(">>> All 3 sample files generated successfully in documents/");
    }
}
