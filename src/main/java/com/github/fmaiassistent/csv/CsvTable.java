package com.github.fmaiassistent.csv;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RFC 4180 reader for the CSV that FM26 Player Export writes. The delimiter is sniffed
 * from the header line because the plugin follows the host locale and emits semicolons
 * wherever a comma would collide with a decimal separator.
 */
public final class CsvTable {
    private static final char[] CANDIDATE_DELIMITERS = {',', ';', '\t', '|'};

    private final List<String> headers;
    private final List<Map<String, String>> rows;

    private CsvTable(List<String> headers, List<Map<String, String>> rows) {
        this.headers = List.copyOf(headers);
        this.rows = List.copyOf(rows);
    }

    public static CsvTable read(Path file) throws IOException {
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    public static CsvTable parse(String content) {
        String text = content.startsWith("﻿") ? content.substring(1) : content;
        if (text.isBlank()) {
            throw new IllegalArgumentException("The CSV file is empty");
        }
        char delimiter = sniffDelimiter(text);
        List<List<String>> records = split(text, delimiter);
        if (records.isEmpty()) {
            throw new IllegalArgumentException("The CSV file has no header row");
        }
        List<String> headers = records.getFirst();
        List<Map<String, String>> rows = new ArrayList<>(records.size() - 1);
        for (List<String> record : records.subList(1, records.size())) {
            if (record.size() == 1 && record.getFirst().isBlank()) {
                continue;
            }
            Map<String, String> row = new LinkedHashMap<>();
            for (int column = 0; column < headers.size(); column++) {
                row.put(headers.get(column), column < record.size() ? record.get(column) : "");
            }
            rows.add(row);
        }
        return new CsvTable(headers, rows);
    }

    public List<String> headers() {
        return headers;
    }

    public List<Map<String, String>> rows() {
        return rows;
    }

    private static char sniffDelimiter(String text) {
        int lineEnd = text.indexOf('\n');
        String header = lineEnd < 0 ? text : text.substring(0, lineEnd);
        char best = ',';
        int bestCount = 0;
        for (char candidate : CANDIDATE_DELIMITERS) {
            int count = countOutsideQuotes(header, candidate);
            if (count > bestCount) {
                best = candidate;
                bestCount = count;
            }
        }
        return best;
    }

    private static int countOutsideQuotes(String line, char delimiter) {
        boolean quoted = false;
        int count = 0;
        for (char ch : line.toCharArray()) {
            if (ch == '"') {
                quoted = !quoted;
            } else if (ch == delimiter && !quoted) {
                count++;
            }
        }
        return count;
    }

    private static List<List<String>> split(String text, char delimiter) {
        List<List<String>> records = new ArrayList<>();
        List<String> record = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(ch);
                }
                continue;
            }
            if (ch == '"') {
                quoted = true;
            } else if (ch == delimiter) {
                record.add(field.toString().trim());
                field.setLength(0);
            } else if (ch == '\n') {
                record.add(field.toString().trim());
                field.setLength(0);
                records.add(record);
                record = new ArrayList<>();
            } else if (ch != '\r') {
                field.append(ch);
            }
        }
        if (!field.isEmpty() || !record.isEmpty()) {
            record.add(field.toString().trim());
            records.add(record);
        }
        return records;
    }
}
