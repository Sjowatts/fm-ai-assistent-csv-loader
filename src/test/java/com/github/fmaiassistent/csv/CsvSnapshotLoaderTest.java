package com.github.fmaiassistent.csv;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CsvSnapshotLoaderTest {
    private final CsvSnapshotLoader loader = new CsvSnapshotLoader();

    @Test
    void mapsAbbreviatedFmColumnsOntoExporterFieldNames() {
        var snapshot = loader.load(CsvTable.parse("""
                Name,Age,Position,Club,Nationality,Wage,Transfer Value,Expires,Acc,Pac,Fin,OtB,Cmp,Ref
                Ollie Watkins,30,ST (C),Aston Villa,ENG,£100K p/w,£40M,30/6/2028,15,16,16,15,14,3
                """), "2026-08-01");

        Map<String, Object> row = snapshot.players().rows().getFirst();
        assertThat(row.get("name")).isEqualTo("Ollie Watkins");
        assertThat(row.get("age")).isEqualTo("30");
        assertThat(row.get("club")).isEqualTo("Aston Villa");
        assertThat(row.get("Acceleration")).isEqualTo(15);
        assertThat(row.get("Pace")).isEqualTo(16);
        assertThat(row.get("Finishing")).isEqualTo(16);
        assertThat(row.get("OffTheBall")).isEqualTo(15);
        assertThat(row.get("Composure")).isEqualTo(14);
        assertThat(row.get("Reflexes")).isEqualTo(3);
        assertThat(row.get("Striker")).isEqualTo(CsvPositions.LISTED);
        assertThat(row.get("Goalkeeper")).isEqualTo(CsvPositions.UNLISTED);
        assertThat(row.get("salary_weekly_raw")).isEqualTo(100_000);
        assertThat(row.get("salary_pa")).isEqualTo(5_200_000);
        assertThat(row.get("asking_price")).isEqualTo(40_000_000L);
        assertThat(row.get("contract_end_date")).isEqualTo("2028-06-30");
    }

    @Test
    void expandsCompoundPositionStringsAcrossEverySide() {
        var ratings = CsvPositions.parse("D/WB (RL), DM, M (C)");

        assertThat(ratings.get("DefenderRight")).isEqualTo(CsvPositions.LISTED);
        assertThat(ratings.get("DefenderLeft")).isEqualTo(CsvPositions.LISTED);
        assertThat(ratings.get("DefenderCentral")).isEqualTo(CsvPositions.UNLISTED);
        assertThat(ratings.get("WingBackRight")).isEqualTo(CsvPositions.LISTED);
        assertThat(ratings.get("WingBackLeft")).isEqualTo(CsvPositions.LISTED);
        assertThat(ratings.get("DefensiveMidfielder")).isEqualTo(CsvPositions.LISTED);
        assertThat(ratings.get("MidfielderCentral")).isEqualTo(CsvPositions.LISTED);
        assertThat(ratings.get("MidfielderRight")).isEqualTo(CsvPositions.UNLISTED);
    }

    @Test
    void readsNatAsNaturalFitnessOnlyWhenItHoldsRatings() {
        var ratingColumn = loader.load(CsvTable.parse("""
                Name,Nationality,Nat
                Boubacar Kamara,FRA,14
                """), null);
        assertThat(ratingColumn.players().rows().getFirst())
                .containsEntry("NaturalFitness", 14)
                .containsEntry("nationality", "FRA");

        var nationColumn = loader.load(CsvTable.parse("""
                Name,Nat
                Boubacar Kamara,FRA
                """), null);
        assertThat(nationColumn.players().rows().getFirst())
                .containsEntry("nationality", "FRA")
                .doesNotContainKey("NaturalFitness");
    }

    @Test
    void treatsScoutedRangesAsMidpointsAndCountsThemAsEstimates() {
        var snapshot = loader.load(CsvTable.parse("""
                Name,Fin,Pac
                Known Player,15,16
                Unscouted Player,10-14,8-12
                """), null);

        assertThat(snapshot.players().rows().getFirst()).containsEntry("Finishing", 15);
        assertThat(snapshot.players().rows().get(1)).containsEntry("Finishing", 12).containsEntry("Pace", 10);
        assertThat(snapshot.playerDiagnostics().estimatedRows()).isEqualTo(1);
    }

    @Test
    void leavesUnpricedAndUnknownCellsUnknownRatherThanZero() {
        var snapshot = loader.load(CsvTable.parse("""
                Name,Transfer Value,Wage,Fin
                Not For Sale Player,Not for Sale,-,N/A
                Free Agent,Free,£0,12
                """), null);

        assertThat(snapshot.players().rows().getFirst())
                .doesNotContainKey("asking_price")
                .doesNotContainKey("salary_weekly_raw")
                .doesNotContainKey("Finishing");
        assertThat(snapshot.players().rows().get(1)).containsEntry("asking_price", 0L);
    }

    @Test
    void linksPlayersToSynthesisedClubsByStableAddress() {
        var snapshot = loader.load(CsvTable.parse("""
                Name,Club
                Player One,Aston Villa
                Player Two,Aston Villa
                Player Three,Leeds United
                """), null);

        assertThat(snapshot.clubs().rows()).hasSize(2);
        Object villaAddress = snapshot.clubs().rows().getFirst().get("sourceAddress");
        assertThat(snapshot.players().rows().getFirst().get("_club_address")).isEqualTo(villaAddress);
        assertThat(snapshot.players().rows().get(1).get("_club_address")).isEqualTo(villaAddress);
        assertThat(snapshot.players().rows().get(2).get("_club_address")).isNotEqualTo(villaAddress);
    }

    @Test
    void derivesAgeFromDateOfBirthWhenTheViewHasNoAgeColumn() {
        var snapshot = loader.load(CsvTable.parse("""
                Name,DoB
                Jacob Ramsey,28/5/2001
                """), "2026-08-01");

        assertThat(snapshot.players().rows().getFirst())
                .containsEntry("date_of_birth", "2001-05-28")
                .containsEntry("age", "25");
    }

    @Test
    void reportsMappedIgnoredAndMissingColumns() {
        var snapshot = loader.load(CsvTable.parse("""
                Name,Club,Fin,Inf,Rec
                Player One,Aston Villa,15,,
                """), null);

        assertThat(snapshot.playerDiagnostics().mappedColumns()).contains("name", "club");
        assertThat(snapshot.playerDiagnostics().ignoredColumns()).contains("Inf", "Rec");
        assertThat(snapshot.playerDiagnostics().missingColumns()).contains("unique_id", "ca", "pa", "position");
        assertThat(snapshot.playerDiagnostics().mappedAttributes()).isEqualTo(1);
    }

    @Test
    void parsesQuotedFieldsAndSemicolonDelimitedExports() {
        var snapshot = loader.load(CsvTable.parse("""
                Name;Club;Position
                "Alexander-Arnold, Trent";Real Madrid;D (R)
                """), null);

        assertThat(snapshot.players().rows().getFirst())
                .containsEntry("name", "Alexander-Arnold, Trent")
                .containsEntry("club", "Real Madrid")
                .containsEntry("DefenderRight", CsvPositions.LISTED);
    }

    @Test
    void rejectsCsvWithoutANameColumn() {
        assertThatThrownBy(() -> loader.load(CsvTable.parse("Club,Age\nAston Villa,30\n"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no player name column");
    }

    @Test
    void mapsStaffCoachingAttributesFromLabelsAndAbbreviations() {
        var snapshot = loader.load(null, CsvTable.parse("""
                Name,Age,Job,Club,Nationality,Wage,Expires,Attacking,Defending,Tactical,Working With Youngsters,JPA,JPP,Man Management,Determination,Sports Science
                Unai Emery,54,Manager,Aston Villa,ESP,£180K p/w,30/6/2029,18,16,19,14,17,16,18,19,12
                """), "2026-08-01");

        Map<String, Object> row = snapshot.staff().rows().getFirst();
        assertThat(row.get("name")).isEqualTo("Unai Emery");
        assertThat(row.get("job")).isEqualTo("Manager");
        assertThat(row.get("age")).isEqualTo(54);
        assertThat(row.get("attacking")).isEqualTo(18);
        assertThat(row.get("tactical")).isEqualTo(19);
        assertThat(row.get("working_with_youngsters")).isEqualTo(14);
        assertThat(row.get("judging_player_ability")).isEqualTo(17);
        assertThat(row.get("judging_player_potential")).isEqualTo(16);
        assertThat(row.get("people_management")).isEqualTo(18);
        assertThat(row.get("sports_science")).isEqualTo(12);
        assertThat(row.get("salary_weekly_raw")).isEqualTo(180_000L);
        assertThat(row.get("contract_end_date")).isEqualTo("2029-06-30");
        assertThat(snapshot.players().rows()).isEmpty();
    }

    @Test
    void sharesOneClubTableBetweenPlayersAndStaff() {
        var snapshot = loader.load(
                CsvTable.parse("""
                        Name,Club
                        Ollie Watkins,Aston Villa
                        """),
                CsvTable.parse("""
                        Name,Job,Club
                        Unai Emery,Manager,Aston Villa
                        Pep Guardiola,Manager,Manchester City
                        """),
                null);

        assertThat(snapshot.clubs().rows()).hasSize(2);
        Object villa = snapshot.clubs().rows().getFirst().get("sourceAddress");
        assertThat(snapshot.players().rows().getFirst().get("_club_address")).isEqualTo(villa);
        assertThat(snapshot.staff().rows().getFirst().get("_club_address")).isEqualTo(villa);
        assertThat(snapshot.staff().rows().get(1).get("_club_address")).isNotEqualTo(villa);
    }

    @Test
    void readsNatAsNationalityOnStaffExportsWhereNaturalFitnessDoesNotExist() {
        var snapshot = loader.load(null, CsvTable.parse("""
                Name,Nat,Job
                Unai Emery,ESP,Manager
                """), null);

        assertThat(snapshot.staff().rows().getFirst()).containsEntry("nationality", "ESP");
    }

    @Test
    void reportsStaffDiagnosticsSeparatelyFromPlayers() {
        var snapshot = loader.load(
                CsvTable.parse("Name,Club,Fin\nOllie Watkins,Aston Villa,16\n"),
                CsvTable.parse("Name,Club,Attacking,Inf\nUnai Emery,Aston Villa,18,\n"),
                null);

        assertThat(snapshot.playerDiagnostics().loadedRows()).isEqualTo(1);
        assertThat(snapshot.playerDiagnostics().mappedAttributes()).isEqualTo(1);
        assertThat(snapshot.staffDiagnostics().loadedRows()).isEqualTo(1);
        assertThat(snapshot.staffDiagnostics().mappedAttributes()).isEqualTo(1);
        assertThat(snapshot.staffDiagnostics().ignoredColumns()).contains("Inf");
        assertThat(snapshot.staffDiagnostics().missingColumns()).contains("job", "ca", "pa");
    }

    @Test
    void rejectsALoadWithNeitherFile() {
        assertThatThrownBy(() -> loader.load((CsvTable) null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("players or a staff export");
    }

    @Test
    void mapsFm26FullNameHeadersFromTheMacosExportPlugin() {
        // Real header shape emitted by the fm26-player-export-macos BepInEx plugin:
        // semicolon-delimited, FM's full attribute names rather than abbreviations,
        // a "Player" name column, and three view-metadata columns FM26 adds.
        var snapshot = loader.load(CsvTable.parse("""
                Picked;Inf;Player;Style;Age;Position;Marking;Technique;Tackling;Penalty Taking;Passing;Aerial Reach;Transfer Value;Long Throws;Long Shots;Heading;Free Kick Taking;First Touch;Finishing;Dribbling;Crossing;Corners;Strength;Stamina;Pace;Natural Fitness;Balance;Jumping Reach;Agility;Acceleration;Throwing;Rushing Out (Tendency);Reflexes;Punching;One On Ones;Kicking;Handling;Eccentricity;Communication;Command Of Area;Aggression;Anticipation;Bravery;Composure;Concentration;Decisions;Determination;Flair;Leadership;Off The Ball;Positioning;Team Work;Vision;Work Rate
                GK;;Mads Kikkenborg;Assertive;26;GK;1;11;1;2;8;15;£700K - £1.5M;3;2;2;7;8;1;1;2;6;12;12;12;13;12;18;12;11;7;11;13;15;13;10;11;8;11;12;14;11;12;12;15;16;7;2;9;2;11;12;11;11
                """), "2026-08-01");

        Map<String, Object> row = snapshot.players().rows().getFirst();
        assertThat(row.get("name")).isEqualTo("Mads Kikkenborg");
        assertThat(row.get("age")).isEqualTo("26");
        assertThat(row.get("Goalkeeper")).isEqualTo(CsvPositions.LISTED);

        // Full FM labels whose text differs from the exporter's field name.
        assertThat(row.get("AerialAbility")).isEqualTo(15);
        assertThat(row.get("Penalties")).isEqualTo(2);
        assertThat(row.get("FreeKicks")).isEqualTo(7);
        assertThat(row.get("NaturalFitness")).isEqualTo(13);
        assertThat(row.get("JumpingReach")).isEqualTo(18);
        assertThat(row.get("RushingOut")).isEqualTo(11);
        assertThat(row.get("OneOnOnes")).isEqualTo(13);
        assertThat(row.get("CommandOfArea")).isEqualTo(12);
        assertThat(row.get("OffTheBall")).isEqualTo(2);
        assertThat(row.get("Teamwork")).isEqualTo(12);
        assertThat(row.get("WorkRate")).isEqualTo(11);

        // FM26 exports this one WITHOUT the "(Tendency)" suffix it keeps on Rushing Out,
        // so "punching" needs its own alias onto TendencyToPunch.
        assertThat(row.get("TendencyToPunch")).isEqualTo(15);

        // "£700K - £1.5M" is a scouting range, stored as its midpoint.
        assertThat(row.get("asking_price")).isEqualTo(1_100_000L);

        assertThat(snapshot.playerDiagnostics().ignoredColumns())
                .contains("Picked", "Inf", "Style");
    }
}
