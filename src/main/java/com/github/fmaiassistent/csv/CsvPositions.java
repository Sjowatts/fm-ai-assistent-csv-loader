package com.github.fmaiassistent.csv;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Expands an FM position string such as "D/WB (R), DM" into the fourteen positional
 * ratings the snapshot schema stores.
 *
 * <p>A view export states only which positions a player can play, never how naturally,
 * so every listed position is written as {@link #LISTED} and everything else as
 * {@link #UNLISTED}. Ratings sourced this way are therefore coarser than the RAM
 * exporter's, which reads the real 1-20 familiarity.
 */
public final class CsvPositions {
    public static final int LISTED = 20;
    public static final int UNLISTED = 1;

    private static final Map<String, List<String>> SIDED_ROLES = Map.of(
            "D", List.of("DefenderLeft", "DefenderCentral", "DefenderRight"),
            "WB", List.of("WingBackLeft", "", "WingBackRight"),
            "M", List.of("MidfielderLeft", "MidfielderCentral", "MidfielderRight"),
            "AM", List.of("AttackingMidfielderLeft", "AttackingMidfielderCentral", "AttackingMidfielderRight"));
    private static final Map<String, String> UNSIDED_ROLES = Map.of(
            "GK", "Goalkeeper",
            "DM", "DefensiveMidfielder",
            "ST", "Striker",
            "S", "Striker",
            "F", "Striker");

    private CsvPositions() {
    }

    public static Map<String, Integer> parse(String raw) {
        Map<String, Integer> ratings = new LinkedHashMap<>();
        allFields().forEach(field -> ratings.put(field, UNLISTED));
        if (CsvValues.isUnknown(raw)) {
            return ratings;
        }
        for (String segment : raw.split(",")) {
            applySegment(segment.trim(), ratings);
        }
        return ratings;
    }

    public static List<String> allFields() {
        return List.of(
                "Goalkeeper", "DefenderLeft", "DefenderCentral", "DefenderRight",
                "WingBackLeft", "DefensiveMidfielder", "WingBackRight",
                "MidfielderLeft", "MidfielderCentral", "MidfielderRight",
                "AttackingMidfielderLeft", "AttackingMidfielderCentral", "AttackingMidfielderRight",
                "Striker");
    }

    private static void applySegment(String segment, Map<String, Integer> ratings) {
        if (segment.isBlank()) {
            return;
        }
        int parenthesis = segment.indexOf('(');
        String rolePart = (parenthesis < 0 ? segment : segment.substring(0, parenthesis)).trim();
        String sidePart = parenthesis < 0
                ? ""
                : segment.substring(parenthesis + 1).replace(")", "").trim().toUpperCase(Locale.ROOT);
        for (String role : rolePart.split("/")) {
            applyRole(role.trim().toUpperCase(Locale.ROOT), sidePart, ratings);
        }
    }

    private static void applyRole(String role, String sides, Map<String, Integer> ratings) {
        String unsided = UNSIDED_ROLES.get(role);
        if (unsided != null) {
            ratings.put(unsided, LISTED);
            return;
        }
        List<String> sided = SIDED_ROLES.get(role);
        if (sided == null) {
            return;
        }
        String applicable = sides.isBlank() ? "C" : sides;
        for (char side : applicable.toCharArray()) {
            int index = switch (side) {
                case 'L' -> 0;
                case 'C' -> 1;
                case 'R' -> 2;
                default -> -1;
            };
            if (index >= 0 && !sided.get(index).isBlank()) {
                ratings.put(sided.get(index), LISTED);
            }
        }
    }
}
