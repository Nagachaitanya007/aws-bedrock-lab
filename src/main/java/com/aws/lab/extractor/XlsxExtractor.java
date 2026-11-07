package com.aws.lab.extractor;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

public class XlsxExtractor implements DocumentExtractor {

    private static final int ROWS_PER_CHUNK = 50;

    @Override
    public boolean supports(String format) {
        return "XLSX".equalsIgnoreCase(format) || "XLS".equalsIgnoreCase(format) || "CSV".equalsIgnoreCase(format);
    }

    @Override
    public List<ExtractedChunk> extract(byte[] rawBytes, String filename) throws Exception {
        List<ExtractedChunk> chunks = new ArrayList<>();
        DataFormatter formatter = new DataFormatter();

        try (ByteArrayInputStream bais = new ByteArrayInputStream(rawBytes);
             XSSFWorkbook workbook = new XSSFWorkbook(bais)) {

            int numberOfSheets = workbook.getNumberOfSheets();

            for (int s = 0; s < numberOfSheets; s++) {
                Sheet sheet = workbook.getSheetAt(s);
                String sheetName = sheet.getSheetName();

                if (sheet.getPhysicalNumberOfRows() == 0) continue;

                // Read header row
                Row headerRow = sheet.getRow(sheet.getFirstRowNum());
                if (headerRow == null) continue;

                List<String> headers = new ArrayList<>();
                for (Cell cell : headerRow) {
                    headers.add(formatter.formatCellValue(cell).trim());
                }

                String headerLine = "| " + String.join(" | ", headers) + " |\n";
                String separatorLine = "|" + " --- |".repeat(headers.size()) + "\n";

                StringBuilder currentChunk = new StringBuilder();
                currentChunk.append(headerLine).append(separatorLine);

                int rowCountInChunk = 0;
                int partNumber = 1;

                for (int r = sheet.getFirstRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) continue;

                    List<String> rowValues = new ArrayList<>();
                    boolean rowHasData = false;

                    for (int c = 0; c < headers.size(); c++) {
                        Cell cell = row.getCell(c);
                        String val = cell != null ? formatter.formatCellValue(cell).trim() : "";
                        if (!val.isEmpty()) rowHasData = true;
                        rowValues.add(val.replace("\n", " "));
                    }

                    if (!rowHasData) continue;

                    currentChunk.append("| ").append(String.join(" | ", rowValues)).append(" |\n");
                    rowCountInChunk++;

                    if (rowCountInChunk >= ROWS_PER_CHUNK) {
                        String sectionTitle = String.format("Sheet: %s (Rows %d-%d)",
                                sheetName, (partNumber - 1) * ROWS_PER_CHUNK + 1, partNumber * ROWS_PER_CHUNK);
                        chunks.add(new ExtractedChunk(sectionTitle, currentChunk.toString()));

                        // Reset chunk with headers
                        currentChunk = new StringBuilder();
                        currentChunk.append(headerLine).append(separatorLine);
                        rowCountInChunk = 0;
                        partNumber++;
                    }
                }

                if (rowCountInChunk > 0) {
                    String sectionTitle = partNumber > 1
                            ? String.format("Sheet: %s (Part %d)", sheetName, partNumber)
                            : "Sheet: " + sheetName;
                    chunks.add(new ExtractedChunk(sectionTitle, currentChunk.toString()));
                }
            }
        }

        if (chunks.isEmpty()) {
            chunks.add(new ExtractedChunk("Spreadsheet", "[Empty or unreadable spreadsheet]"));
        }

        return chunks;
    }
}
