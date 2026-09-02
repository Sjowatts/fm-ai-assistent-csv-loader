package com.github.fmaiassistent.service;

import com.github.fmaiassistent.config.JCacheConfiguration;
import com.github.fmaiassistent.csv.CsvSnapshotLoader;
import com.github.fmaiassistent.csv.CsvTable;
import com.github.fmaiassistent.domain.entity.LoadMetadataEntity;
import com.github.fmaiassistent.managedclub.ManagedClubContextService;
import com.github.fmaiassistent.repository.DatabaseService;
import com.github.fmaiassistent.repository.LoadMetadataRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Replaces the snapshot from a CSV exported out of FM instead of from the running
 * process. Persistence is deliberately the same {@link SnapshotDatabaseWriter} path the
 * RAM load uses, so every query, MCP tool and UI view behaves identically afterwards.
 */
@Service
public class CsvLoadAllService {
    private static final Logger log = LoggerFactory.getLogger(CsvLoadAllService.class);

    private final DatabaseService databaseService;
    private final SnapshotDatabaseWriter snapshotWriter;
    private final ManagedClubContextService managedClubContexts;
    private final LoadMetadataRepository metadata;
    private final CsvSnapshotLoader loader = new CsvSnapshotLoader();

    public CsvLoadAllService(
            DatabaseService databaseService,
            SnapshotDatabaseWriter snapshotWriter,
            ManagedClubContextService managedClubContexts,
            LoadMetadataRepository metadata) {
        this.databaseService = databaseService;
        this.snapshotWriter = snapshotWriter;
        this.managedClubContexts = managedClubContexts;
        this.metadata = metadata;
    }

    @Caching(evict = {
            @CacheEvict(cacheNames = JCacheConfiguration.PLAYERS_CACHE, allEntries = true),
            @CacheEvict(cacheNames = JCacheConfiguration.PLAYERS_WITH_CLUBS_CACHE, allEntries = true),
            @CacheEvict(cacheNames = JCacheConfiguration.STAFF_WITH_CLUBS_CACHE, allEntries = true),
            @CacheEvict(cacheNames = JCacheConfiguration.NATIONS_CACHE, allEntries = true),
            @CacheEvict(cacheNames = JCacheConfiguration.COMPETITIONS_CACHE, allEntries = true),
            @CacheEvict(cacheNames = JCacheConfiguration.CLUB_NAMES_CACHE, allEntries = true),
            @CacheEvict(cacheNames = JCacheConfiguration.CLUB_CACHE, allEntries = true),
            @CacheEvict(cacheNames = JCacheConfiguration.PLAYER_MAPPING_CACHE, allEntries = true)
    })
    public CsvLoadResult loadCsv(Path playersCsv, Path staffCsv, String gameDate, String managedClub)
            throws IOException {
        if (playersCsv == null && staffCsv == null) {
            throw new IllegalArgumentException("Pass a players CSV, a staff CSV, or both");
        }
        requireFile(playersCsv, "players");
        requireFile(staffCsv, "staff");
        return loadTables(
                playersCsv == null ? null : CsvTable.read(playersCsv),
                staffCsv == null ? null : CsvTable.read(staffCsv),
                sourceFiles(playersCsv, staffCsv),
                gameDate,
                managedClub);
    }

    /**
     * Loads already-parsed tables, for callers such as the UI that hold uploaded bytes
     * rather than a file on disk.
     */
    public CsvLoadResult loadUploads(
            String playersCsv, String staffCsv, String sourceLabel, String gameDate, String managedClub) {
        if (blank(playersCsv) && blank(staffCsv)) {
            throw new IllegalArgumentException("Pass a players CSV, a staff CSV, or both");
        }
        return loadTables(
                blank(playersCsv) ? null : CsvTable.parse(playersCsv),
                blank(staffCsv) ? null : CsvTable.parse(staffCsv),
                sourceLabel,
                gameDate,
                managedClub);
    }

    @Transactional(rollbackFor = Exception.class)
    public CsvLoadResult loadTables(
            CsvTable playersCsv, CsvTable staffCsv, String sourceLabel, String gameDate, String managedClub) {
        long started = System.nanoTime();
        CsvSnapshotLoader.CsvSnapshot snapshot = loader.load(playersCsv, staffCsv, gameDate);
        if (snapshot.players().rows().isEmpty() && snapshot.staff().rows().isEmpty()) {
            throw new IllegalArgumentException("The CSV contained no named people");
        }

        // A load replaces the whole snapshot, so a players-only load clears any staff that
        // an earlier load wrote, and the other way round. Pass both files to keep both.
        databaseService.clearAllTables();
        Map<Long, Long> clubIds = snapshotWriter.saveClubs(snapshot.clubs(), Map.of());
        snapshotWriter.savePlayers(snapshot.players(), clubIds);
        snapshotWriter.saveStaff(snapshot.staff(), clubIds);

        String snapshotId = UUID.randomUUID().toString();
        String loadedAt = OffsetDateTime.now().toString();
        List<LoadMetadataEntity> snapshotMetadata = new ArrayList<>(List.of(
                new LoadMetadataEntity("snapshot_id", snapshotId),
                new LoadMetadataEntity("source", "csv"),
                new LoadMetadataEntity("source_file", sourceLabel == null ? "" : sourceLabel),
                new LoadMetadataEntity("players_count", String.valueOf(snapshot.players().rows().size())),
                new LoadMetadataEntity("staff_count", String.valueOf(snapshot.staff().rows().size())),
                new LoadMetadataEntity("clubs_count", String.valueOf(snapshot.clubs().rows().size())),
                new LoadMetadataEntity("competitions_count", "0"),
                new LoadMetadataEntity("game_date", gameDate == null ? "" : gameDate),
                new LoadMetadataEntity("loaded_at", loadedAt),
                new LoadMetadataEntity("clubs_loaded_at", loadedAt)));
        metadata.saveAll(snapshotMetadata);

        if (managedClub == null || managedClub.isBlank()) {
            managedClubContexts.markUnavailable(
                    "A CSV snapshot has no managed club. Pass managed_club when loading to enable squad tools.");
        } else {
            managedClubContexts.markUnavailable(
                    "Managed club reported as " + managedClub + " by the CSV load");
        }

        log.info("FM26 CSV snapshot loaded in {} ms: {} players, {} staff, {} clubs",
                (System.nanoTime() - started) / 1_000_000,
                snapshot.players().rows().size(),
                snapshot.staff().rows().size(),
                snapshot.clubs().rows().size());
        return new CsvLoadResult(
                snapshotId,
                gameDate,
                snapshot.players().rows().size(),
                snapshot.staff().rows().size(),
                snapshot.clubs().rows().size(),
                snapshot.playerDiagnostics(),
                snapshot.staffDiagnostics());
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static void requireFile(Path csv, String kind) {
        if (csv != null && !Files.isRegularFile(csv)) {
            throw new IllegalArgumentException("No " + kind + " CSV file at " + csv);
        }
    }

    private static String sourceFiles(Path playersCsv, Path staffCsv) {
        StringBuilder out = new StringBuilder();
        if (playersCsv != null) {
            out.append(playersCsv);
        }
        if (staffCsv != null) {
            out.append(out.isEmpty() ? "" : "; ").append(staffCsv);
        }
        return out.toString();
    }

    public record CsvLoadResult(
            String snapshotId,
            String gameDate,
            int players,
            int staff,
            int clubs,
            CsvSnapshotLoader.Diagnostics playerDiagnostics,
            CsvSnapshotLoader.Diagnostics staffDiagnostics) {
        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("snapshot_id", snapshotId);
            out.put("game_date", gameDate);
            out.put("source", "csv");
            out.put("players", players);
            out.put("staff", staff);
            out.put("clubs", clubs);
            out.put("players_detail", playerDiagnostics.toMap());
            out.put("staff_detail", staffDiagnostics.toMap());
            return out;
        }
    }
}
