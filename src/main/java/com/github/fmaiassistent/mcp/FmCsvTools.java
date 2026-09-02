package com.github.fmaiassistent.mcp;

import com.github.fmaiassistent.service.CsvLoadAllService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

@Service
public class FmCsvTools {
    private final CsvLoadAllService csvLoader;

    public FmCsvTools(CsvLoadAllService csvLoader) {
        this.csvLoader = csvLoader;
    }

    @Tool(name = "fm26_load_csv", description = """
            Replace the local snapshot from CSV views exported out of FM26, for example by the FM26 Player \
            Export plugin. Use this instead of fm26_refresh_data on macOS, or whenever FM's memory cannot be \
            read. Pass a players export, a staff export, or both. A load replaces the entire snapshot, so \
            loading only one of them clears the other: send both paths in one call to keep both. The result \
            reports which columns were mapped, ignored and missing per file: read missing_columns before \
            trusting any tool that depends on them, and treat rows counted in \
            rows_with_estimated_attributes as scouting estimates rather than exact values.""")
    public Map<String, Object> loadCsv(
            @ToolParam(required = false, description = "Absolute path to the exported players CSV") String path,
            @ToolParam(required = false, description = "Absolute path to the exported staff CSV") String staffPath,
            @ToolParam(required = false, description = "In-game date of the export as an ISO date, for example 2026-08-01. Needed to derive ages when the view has no Age column.") String gameDate,
            @ToolParam(required = false, description = "Name of the club the user manages, exactly as it appears in the CSV") String managedClub)
            throws IOException {
        return csvLoader.loadCsv(
                        path == null || path.isBlank() ? null : Path.of(path),
                        staffPath == null || staffPath.isBlank() ? null : Path.of(staffPath),
                        gameDate,
                        managedClub)
                .toMap();
    }
}
