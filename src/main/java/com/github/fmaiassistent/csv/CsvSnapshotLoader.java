package com.github.fmaiassistent.csv;

import com.github.fmaiassistent.exporter.ClubExporter;
import com.github.fmaiassistent.exporter.PlayerExporter;
import com.github.fmaiassistent.exporter.StaffExporter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Turns exported FM views into the same shape the RAM exporters produce, so a CSV
 * snapshot can be persisted and queried by exactly the same code path.
 *
 * <p>Rows are correlated by synthetic addresses. {@code SnapshotDatabaseWriter} only ever
 * uses those values as join handles between people and clubs, never as real pointers, so
 * a stable hash of the club name serves the same purpose as the RAM address did. Players
 * and staff are loaded together because they share one synthesised club table.
 */
public final class CsvSnapshotLoader {
    private static final long CLUB_ADDRESS_SEED = 0x51C5_0000_0000L;
    private static final long PLAYER_ADDRESS_SEED = 0x91A7_0000_0000L;
    private static final long STAFF_ADDRESS_SEED = 0x57AF_0000_0000L;
    private static final List<String> PLAYER_HIGH_VALUE_COLUMNS = List.of(
            "unique_id", "position", "age", "club", "ca", "pa", "asking_price",
            "salary_weekly_raw", "contract_end_date", "nationality");
    private static final List<String> STAFF_HIGH_VALUE_COLUMNS = List.of(
            "unique_id", "job", "age", "club", "ca", "pa", "salary_weekly_raw",
            "contract_end_date", "nationality");

    public CsvSnapshot load(Path playersCsv, Path staffCsv, String gameDate) throws IOException {
        return load(
                playersCsv == null ? null : CsvTable.read(playersCsv),
                staffCsv == null ? null : CsvTable.read(staffCsv),
                gameDate);
    }

    public CsvSnapshot load(CsvTable players, String gameDate) {
        return load(players, null, gameDate);
    }

    public CsvSnapshot load(CsvTable players, CsvTable staff, String gameDate) {
        if (players == null && staff == null) {
            throw new IllegalArgumentException("Loading a CSV snapshot needs a players or a staff export");
        }
        LocalDate asOf = parseDate(gameDate);
        Set<String> clubNames = new LinkedHashSet<>();
        PeopleTable playerRows = players == null
                ? PeopleTable.empty()
                : readPlayers(players, asOf, clubNames);
        PeopleTable staffRows = staff == null
                ? PeopleTable.empty()
                : readStaff(staff, asOf, clubNames);

        List<Map<String, Object>> clubs = new ArrayList<>(clubNames.size());
        for (String club : clubNames) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sourceAddress", clubAddress(club));
            row.put("name", club);
            row.put("gender", "");
            row.put("competition", "");
            row.put("nation", "");
            row.put("reputation", 0);
            clubs.add(row);
        }

        String safeGameDate = gameDate == null ? "" : gameDate;
        return new CsvSnapshot(
                new PlayerExporter.ExportResult(safeGameDate, playerRows.rows()),
                new StaffExporter.ExportResult(safeGameDate, staffRows.rows()),
                new ClubExporter.ExportResult(List.copyOf(clubs)),
                playerRows.diagnostics(),
                staffRows.diagnostics());
    }

    private PeopleTable readPlayers(CsvTable table, LocalDate asOf, Set<String> clubNames) {
        CsvColumnMapping mapping = CsvColumnMapping.of(table, CsvColumnMapping.Kind.PLAYER);
        List<Map<String, Object>> rows = new ArrayList<>(table.rows().size());
        int estimated = 0;
        int index = 0;

        for (Map<String, String> source : table.rows()) {
            String name = value(source, mapping, "name").orElse("");
            if (name.isBlank()) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            String club = value(source, mapping, "club").orElse("");
            String playingClub = value(source, mapping, "playing_club").orElse(club);
            collectClubs(clubNames, club, playingClub);

            row.put("index", String.valueOf(index));
            row.put("record", String.valueOf(PLAYER_ADDRESS_SEED + index));
            row.put("name", name);
            row.put("club", club);
            row.put("playing_club", playingClub);
            row.put("loan_club", club.equals(playingClub) ? "" : playingClub);
            row.put("is_loaned_out", club.equals(playingClub) ? "" : "Yes");
            row.put("_club_address", clubAddress(club));
            row.put("_playing_club_address", clubAddress(playingClub.isBlank() ? club : playingClub));

            putUniqueId(row, source, mapping);
            putText(row, source, mapping, "nationality", "gender", "injury");
            value(source, mapping, "height_cm").flatMap(CsvValues::heightCm)
                    .ifPresent(height -> row.put("height_cm", height));
            putAbility(row, source, mapping, "ca", "pa");
            putInteger(row, source, mapping,
                    "current_reputation", "home_reputation", "world_reputation");

            value(source, mapping, "salary_weekly_raw").flatMap(CsvValues::money).ifPresent(weekly -> {
                row.put("salary_weekly_raw", weekly.intValue());
                row.put("salary_pa", (int) Math.min(Integer.MAX_VALUE, weekly * 52));
            });
            value(source, mapping, "asking_price").flatMap(CsvValues::money).ifPresent(price -> {
                row.put("asking_price", price);
                row.put("asking_price_raw", price);
            });

            putDate(row, source, mapping, "contract_end_date", "joined_club_date");
            Optional<String> dateOfBirth = value(source, mapping, "date_of_birth").flatMap(CsvValues::date);
            dateOfBirth.ifPresent(date -> row.put("date_of_birth", date));
            age(value(source, mapping, "age"), dateOfBirth, asOf)
                    .ifPresent(age -> {
                        row.put("age", String.valueOf(age));
                        if (asOf != null) {
                            row.put("age_as_of", asOf.toString());
                        }
                    });

            putFlag(row, source, mapping, "transfer_listed", "listed_for_loan", "injured");
            CsvPositions.parse(value(source, mapping, "position").orElse("")).forEach(row::put);

            if (putAttributes(row, source, mapping)) {
                estimated++;
            }
            rows.add(row);
            index++;
        }
        return new PeopleTable(List.copyOf(rows), diagnostics(table, mapping, rows.size(), estimated,
                PLAYER_HIGH_VALUE_COLUMNS));
    }

    private PeopleTable readStaff(CsvTable table, LocalDate asOf, Set<String> clubNames) {
        CsvColumnMapping mapping = CsvColumnMapping.of(table, CsvColumnMapping.Kind.STAFF);
        List<Map<String, Object>> rows = new ArrayList<>(table.rows().size());
        int estimated = 0;
        int index = 0;

        for (Map<String, String> source : table.rows()) {
            String name = value(source, mapping, "name").orElse("");
            if (name.isBlank()) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            String club = value(source, mapping, "club").orElse("");
            collectClubs(clubNames, club);

            row.put("staff_index", index);
            row.put("record_address", "0x" + Long.toHexString(STAFF_ADDRESS_SEED + index));
            row.put("name", name);
            row.put("club", club);
            row.put("_club_address", clubAddress(club));

            putUniqueId(row, source, mapping);
            putText(row, source, mapping, "nationality", "gender", "division", "job");
            putAbility(row, source, mapping, "ca", "pa");
            putInteger(row, source, mapping,
                    "current_reputation", "home_reputation", "world_reputation");
            value(source, mapping, "salary_weekly_raw").flatMap(CsvValues::money)
                    .ifPresent(weekly -> row.put("salary_weekly_raw", weekly));
            putDate(row, source, mapping, "contract_end_date");
            Optional<String> dateOfBirth = value(source, mapping, "date_of_birth").flatMap(CsvValues::date);
            dateOfBirth.ifPresent(date -> row.put("date_of_birth", date));
            age(value(source, mapping, "age"), dateOfBirth, asOf)
                    .ifPresent(age -> {
                        row.put("age", age);
                        if (asOf != null) {
                            row.put("age_as_of", asOf.toString());
                        }
                    });

            if (putAttributes(row, source, mapping)) {
                estimated++;
            }
            rows.add(row);
            index++;
        }
        return new PeopleTable(List.copyOf(rows), diagnostics(table, mapping, rows.size(), estimated,
                STAFF_HIGH_VALUE_COLUMNS));
    }

    private static boolean putAttributes(
            Map<String, Object> row, Map<String, String> source, CsvColumnMapping mapping) {
        boolean estimated = false;
        for (Map.Entry<String, String> attribute : mapping.attributeFieldsByHeader().entrySet()) {
            String raw = source.get(attribute.getKey());
            estimated |= CsvValues.isRange(raw);
            CsvValues.attribute(raw).ifPresent(rating -> row.put(attribute.getValue(), rating));
        }
        return estimated;
    }

    private static void putUniqueId(
            Map<String, Object> row, Map<String, String> source, CsvColumnMapping mapping) {
        value(source, mapping, "unique_id").flatMap(CsvValues::integer)
                .ifPresent(uid -> row.put("unique_id", uid.longValue()));
    }

    private static void putText(
            Map<String, Object> row, Map<String, String> source, CsvColumnMapping mapping, String... targets) {
        for (String target : targets) {
            value(source, mapping, target).ifPresent(text -> row.put(target, text));
        }
    }

    private static void putInteger(
            Map<String, Object> row, Map<String, String> source, CsvColumnMapping mapping, String... targets) {
        for (String target : targets) {
            value(source, mapping, target).flatMap(CsvValues::integer)
                    .ifPresent(number -> row.put(target, number));
        }
    }

    /**
     * Current and potential ability. FM stores potential as a negative code (-1 to -10) when a
     * player's PA is a randomised range rather than a settled value; those are not ability
     * scores, so they are left unknown rather than written through as negative numbers.
     */
    private static void putAbility(
            Map<String, Object> row, Map<String, String> source, CsvColumnMapping mapping, String... targets) {
        for (String target : targets) {
            value(source, mapping, target).flatMap(CsvValues::integer)
                    .filter(number -> number > 0)
                    .ifPresent(number -> row.put(target, number));
        }
    }

    private static void putDate(
            Map<String, Object> row, Map<String, String> source, CsvColumnMapping mapping, String... targets) {
        for (String target : targets) {
            value(source, mapping, target).flatMap(CsvValues::date).ifPresent(date -> row.put(target, date));
        }
    }

    private static void putFlag(
            Map<String, Object> row, Map<String, String> source, CsvColumnMapping mapping, String... targets) {
        for (String target : targets) {
            value(source, mapping, target).flatMap(CsvValues::flag).ifPresent(flag -> row.put(target, flag));
        }
    }

    private static Optional<Integer> age(
            Optional<String> rawAge, Optional<String> dateOfBirth, LocalDate asOf) {
        Optional<Integer> stated = rawAge.flatMap(CsvValues::integer);
        if (stated.isPresent()) {
            return stated;
        }
        if (dateOfBirth.isPresent() && asOf != null) {
            return Optional.of(Period.between(LocalDate.parse(dateOfBirth.get()), asOf).getYears());
        }
        return Optional.empty();
    }

    private static void collectClubs(Set<String> clubNames, String... clubs) {
        for (String club : clubs) {
            if (club != null && !club.isBlank()) {
                clubNames.add(club);
            }
        }
    }

    private static Diagnostics diagnostics(
            CsvTable table, CsvColumnMapping mapping, int loaded, int estimated, List<String> highValueColumns) {
        List<String> missing = new ArrayList<>();
        for (String target : highValueColumns) {
            if (!mapping.has(target)) {
                missing.add(target);
            }
        }
        return new Diagnostics(
                table.rows().size(),
                loaded,
                estimated,
                mapping.attributeFieldsByHeader().size(),
                mapping.mappedTargets(),
                mapping.unmappedHeaders(),
                missing);
    }

    private static Optional<String> value(
            Map<String, String> row, CsvColumnMapping mapping, String target) {
        return mapping.header(target)
                .map(row::get)
                .filter(value -> !CsvValues.isUnknown(value))
                .map(String::trim);
    }

    private static LocalDate parseDate(String gameDate) {
        if (gameDate == null || gameDate.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(gameDate);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("game_date must be an ISO date such as 2026-08-01");
        }
    }

    private static long clubAddress(String club) {
        if (club == null || club.isBlank()) {
            return 0;
        }
        long hash = 0xcbf2_9ce4_8422_2325L;
        for (byte b : club.toLowerCase().getBytes(StandardCharsets.UTF_8)) {
            hash = (hash ^ (b & 0xff)) * 0x1000_0000_01b3L;
        }
        return CLUB_ADDRESS_SEED + Math.abs(hash % 0xFFFF_FFFFL);
    }

    private record PeopleTable(List<Map<String, Object>> rows, Diagnostics diagnostics) {
        static PeopleTable empty() {
            return new PeopleTable(List.of(), Diagnostics.empty());
        }
    }

    public record CsvSnapshot(
            PlayerExporter.ExportResult players,
            StaffExporter.ExportResult staff,
            ClubExporter.ExportResult clubs,
            Diagnostics playerDiagnostics,
            Diagnostics staffDiagnostics) {
    }

    public record Diagnostics(
            int csvRows,
            int loadedRows,
            int estimatedRows,
            int mappedAttributes,
            List<String> mappedColumns,
            List<String> ignoredColumns,
            List<String> missingColumns) {
        public static Diagnostics empty() {
            return new Diagnostics(0, 0, 0, 0, List.of(), List.of(), List.of());
        }

        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("csv_rows", csvRows);
            out.put("loaded", loadedRows);
            out.put("rows_with_estimated_attributes", estimatedRows);
            out.put("mapped_attributes", mappedAttributes);
            out.put("mapped_columns", mappedColumns);
            out.put("ignored_columns", ignoredColumns);
            out.put("missing_columns", missingColumns);
            return out;
        }
    }
}
