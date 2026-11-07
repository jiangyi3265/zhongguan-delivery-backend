package com.ruoyi.qicai;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.apache.logging.log4j.LogManager;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exercise the application-facing behavior affected by the aligned dependency families. */
class DependencyCompatibilityTest
{
    public static class Receipt
    {
        public BigDecimal price;
        public LocalDateTime createdAt;
        @JsonIgnore public String internal = "server-only";
    }

    @Test void jsonPreservesMoneyDatesAndIgnoredProperties() throws Exception
    {
        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        Receipt value = json.readValue("{\"price\":29.90,\"createdAt\":\"2026-10-18T09:00:00\",\"internal\":\"client\"}", Receipt.class);
        assertEquals(new BigDecimal("29.90"), value.price);
        assertEquals(LocalDateTime.of(2026, 10, 18, 9, 0), value.createdAt);
        assertEquals("server-only", value.internal);
        String encoded = json.writeValueAsString(value);
        assertTrue(encoded.contains("29.90"));
        assertFalse(encoded.contains("internal"));
        assertEquals(value.createdAt, json.readValue(encoded, Receipt.class).createdAt);
    }

    @Test void pathologicalJsonNestingFailsWithAParserException()
    {
        StringBuilder json = new StringBuilder();
        for (int i = 0; i < 1100; i++) json.append('[');
        json.append('0');
        for (int i = 0; i < 1100; i++) json.append(']');
        assertThrows(JsonProcessingException.class, () -> new ObjectMapper().readTree(json.toString()));
    }

    @Test void officeWorkbookZipRoundtripPreservesBusinessValues() throws Exception
    {
        byte[] bytes;
        try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream())
        {
            book.createSheet("Products").createRow(0).createCell(0).setCellValue("TEST-SKU-001");
            book.getSheetAt(0).getRow(0).createCell(1).setCellValue(29.9);
            book.getSheetAt(0).getRow(0).createCell(2).setCellValue(100);
            book.write(output);
            bytes = output.toByteArray();
        }
        try (XSSFWorkbook restored = new XSSFWorkbook(new ByteArrayInputStream(bytes)))
        {
            assertEquals("TEST-SKU-001", restored.getSheetAt(0).getRow(0).getCell(0).getStringCellValue());
            assertEquals(29.9, restored.getSheetAt(0).getRow(0).getCell(1).getNumericCellValue(), 0.000001);
            assertEquals(100, restored.getSheetAt(0).getRow(0).getCell(2).getNumericCellValue(), 0.000001);
        }
    }

    @Test void malformedOfficeZipIsRejected()
    {
        byte[] invalid = new byte[] { 'P', 'K', 3, 4, 0, 0, 0, 0 };
        assertThrows(Exception.class, () -> { try (XSSFWorkbook ignored = new XSSFWorkbook(new ByteArrayInputStream(invalid))) {} });
    }

    @Test void log4jUsesTheExistingSlf4jBridge()
    {
        org.apache.logging.log4j.Logger logger = LogManager.getLogger("dependency-compatibility");
        assertTrue(logger.getClass().getName().startsWith("org.apache.logging.slf4j."));
        logger.info("Dependency compatibility logging probe");
    }
}
