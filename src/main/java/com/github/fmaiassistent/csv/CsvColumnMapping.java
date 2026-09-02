package com.github.fmaiassistent.csv;

import com.github.fmaiassistent.player.AttributeDefinitions;
import com.github.fmaiassistent.player.FieldDef;
import com.github.fmaiassistent.staff.StaffAttributeDefinitions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Resolves the headers of an exported FM view against the field names the snapshot
 * writer expects. Both FM's abbreviated column captions ("Cmp", "OtB") and its full
 * attribute names are accepted.
 */
public final class CsvColumnMapping {
    private static final Map<String, String> IDENTITY_ALIASES = identityAliases();
    private static final Map<String, String> ATTRIBUTE_ALIASES = attributeAliases();
    private static final Map<String, String> STAFF_IDENTITY_ALIASES = staffIdentityAliases();
    private static final Map<String, String> STAFF_ATTRIBUTE_ALIASES = staffAttributeAliases();

    private final Map<String, String> headersByTarget;
    private final Map<String, String> attributeFieldsByHeader;
    private final List<String> unmappedHeaders;

    private CsvColumnMapping(
            Map<String, String> headersByTarget,
            Map<String, String> attributeFieldsByHeader,
            List<String> unmappedHeaders) {
        this.headersByTarget = Map.copyOf(headersByTarget);
        this.attributeFieldsByHeader = Map.copyOf(attributeFieldsByHeader);
        this.unmappedHeaders = List.copyOf(unmappedHeaders);
    }

    public static CsvColumnMapping of(CsvTable table) {
        return of(table, Kind.PLAYER);
    }

    public static CsvColumnMapping of(CsvTable table, Kind kind) {
        Map<String, String> identityAliases = kind == Kind.STAFF ? STAFF_IDENTITY_ALIASES : IDENTITY_ALIASES;
        Map<String, String> attributeAliases = kind == Kind.STAFF ? STAFF_ATTRIBUTE_ALIASES : ATTRIBUTE_ALIASES;
        Map<String, String> headersByTarget = new LinkedHashMap<>();
        Map<String, String> attributeFieldsByHeader = new LinkedHashMap<>();
        List<String> unmapped = new ArrayList<>();
        for (String header : table.headers()) {
            String key = normalize(header);
            if (key.isEmpty()) {
                continue;
            }
            if ("nat".equals(key) && kind == Kind.PLAYER) {
                if (readsAsRating(table, header)) {
                    attributeFieldsByHeader.put(header, "NaturalFitness");
                } else {
                    headersByTarget.putIfAbsent("nationality", header);
                }
                continue;
            }
            String attribute = attributeAliases.get(key);
            if (attribute != null) {
                attributeFieldsByHeader.put(header, attribute);
                continue;
            }
            String target = identityAliases.get(key);
            if (target != null) {
                headersByTarget.putIfAbsent(target, header);
                continue;
            }
            unmapped.add(header);
        }
        if (!headersByTarget.containsKey("name")) {
            throw new IllegalArgumentException("The CSV has no "
                    + (kind == Kind.STAFF ? "staff" : "player")
                    + " name column. Add Name to the FM view before exporting.");
        }
        return new CsvColumnMapping(headersByTarget, attributeFieldsByHeader, unmapped);
    }

    public Optional<String> header(String target) {
        return Optional.ofNullable(headersByTarget.get(target));
    }

    public boolean has(String target) {
        return headersByTarget.containsKey(target);
    }

    public Map<String, String> attributeFieldsByHeader() {
        return attributeFieldsByHeader;
    }

    public List<String> unmappedHeaders() {
        return unmappedHeaders;
    }

    public List<String> mappedTargets() {
        return List.copyOf(headersByTarget.keySet());
    }

    /**
     * FM abbreviates both Nationality and Natural Fitness as "Nat". A column whose values
     * all read as 1-20 ratings is the attribute; anything else is the nation.
     */
    private static boolean readsAsRating(CsvTable table, String header) {
        int sampled = 0;
        for (Map<String, String> row : table.rows()) {
            String value = row.get(header);
            if (CsvValues.isUnknown(value)) {
                continue;
            }
            if (CsvValues.attribute(value).isEmpty() || !value.trim().chars().allMatch(
                    ch -> Character.isDigit(ch) || ch == '-' || ch == ' ')) {
                return false;
            }
            if (++sampled >= 20) {
                break;
            }
        }
        return sampled > 0;
    }

    static String normalize(String header) {
        StringBuilder out = new StringBuilder();
        for (char ch : header.toCharArray()) {
            if (Character.isLetterOrDigit(ch)) {
                out.append(Character.toLowerCase(ch));
            }
        }
        return out.toString();
    }

    private static Map<String, String> identityAliases() {
        Map<String, String> aliases = new HashMap<>();
        put(aliases, "name", "name", "player", "playername");
        put(aliases, "unique_id", "uid", "uniqueid", "fmuid", "playerid");
        put(aliases, "age", "age");
        put(aliases, "nationality", "nationality", "nation", "nationalities", "country");
        put(aliases, "club", "club", "team", "currentclub");
        put(aliases, "playing_club", "playingclub", "onloanat", "loanclub");
        put(aliases, "position", "position", "positions", "positionsselected", "pos1");
        put(aliases, "height_cm", "height");
        put(aliases, "salary_weekly_raw", "wage", "salary", "wages", "weeklywage");
        put(aliases, "asking_price", "transfervalue", "value", "askingprice", "transferfee");
        put(aliases, "contract_end_date", "expires", "contractexpires", "contractend", "contractenddate", "expiry");
        put(aliases, "date_of_birth", "dob", "dateofbirth", "born", "birthdate");
        put(aliases, "joined_club_date", "joined", "joinedclub", "signed");
        put(aliases, "ca", "ca", "currentability");
        put(aliases, "pa", "pa", "potentialability");
        put(aliases, "gender", "gender", "sex");
        put(aliases, "current_reputation", "reputation", "currentreputation");
        put(aliases, "home_reputation", "homereputation");
        put(aliases, "world_reputation", "worldreputation");
        put(aliases, "transfer_listed", "transferstatus", "transferlisted");
        put(aliases, "listed_for_loan", "loanstatus", "loanlisted", "listedforloan");
        put(aliases, "injured", "injured");
        put(aliases, "injury", "injury", "injurystatus");
        return Map.copyOf(aliases);
    }

    private static Map<String, String> attributeAliases() {
        Map<String, String> aliases = new HashMap<>();
        Stream.concat(
                        AttributeDefinitions.VISIBLE_FIELDS.stream(),
                        AttributeDefinitions.HIDDEN_DIRECT_FIELDS.stream())
                .map(FieldDef::name)
                .forEach(name -> aliases.put(normalize(name), name));

        put(aliases, "Corners", "cor");
        put(aliases, "Crossing", "cro");
        put(aliases, "Dribbling", "dri");
        put(aliases, "Finishing", "fin");
        put(aliases, "FirstTouch", "fir", "firsttouch");
        put(aliases, "FreeKicks", "fre", "freekicktaking", "freekicks");
        put(aliases, "Heading", "hea");
        put(aliases, "LongShots", "lon", "longshots");
        put(aliases, "LongThrows", "lth", "longthrows");
        put(aliases, "Marking", "mar");
        put(aliases, "Passing", "pas");
        put(aliases, "Penalties", "pen", "penaltytaking");
        put(aliases, "Tackling", "tck");
        put(aliases, "Technique", "tec");
        put(aliases, "Aggression", "agg");
        put(aliases, "Anticipation", "ant");
        put(aliases, "Bravery", "bra");
        put(aliases, "Composure", "cmp");
        put(aliases, "Concentration", "cnt");
        put(aliases, "Decisions", "dec");
        put(aliases, "Determination", "det");
        put(aliases, "Flair", "fla");
        put(aliases, "Leadership", "ldr");
        put(aliases, "OffTheBall", "otb", "offtheball");
        put(aliases, "Positioning", "pos");
        put(aliases, "Teamwork", "tea");
        put(aliases, "Vision", "vis");
        put(aliases, "WorkRate", "wor", "workrate");
        put(aliases, "Acceleration", "acc");
        put(aliases, "Agility", "agi");
        put(aliases, "Balance", "bal");
        put(aliases, "JumpingReach", "jum", "jumpingreach", "jumping");
        put(aliases, "NaturalFitness", "naturalfitness");
        put(aliases, "Pace", "pac");
        put(aliases, "Stamina", "sta");
        put(aliases, "Strength", "str");
        put(aliases, "AerialAbility", "aer", "aerialreach", "aerialability");
        put(aliases, "CommandOfArea", "cmd", "commandofarea");
        put(aliases, "Communication", "com");
        put(aliases, "Eccentricity", "ecc");
        put(aliases, "Handling", "han");
        put(aliases, "Kicking", "kic");
        put(aliases, "OneOnOnes", "1v1", "oneonones");
        put(aliases, "TendencyToPunch", "pun", "punchingtendency", "tendencytopunch");
        put(aliases, "Reflexes", "ref");
        put(aliases, "RushingOut", "tro", "rushingouttendency", "rushingout");
        put(aliases, "Throwing", "thr");
        put(aliases, "LeftFoot", "lfoot", "leftfoot", "lft");
        put(aliases, "RightFoot", "rfoot", "rightfoot", "rft");
        put(aliases, "Versatility", "ver", "versatility");
        put(aliases, "Consistency", "con", "consistency");
        put(aliases, "ImportantMatches", "imp", "importantmatches");
        put(aliases, "InjuryProneness", "inj", "injuryproneness");
        put(aliases, "Dirtiness", "dirtiness");
        return Map.copyOf(aliases);
    }

    private static Map<String, String> staffIdentityAliases() {
        Map<String, String> aliases = new HashMap<>();
        put(aliases, "name", "name", "staff", "staffname");
        put(aliases, "unique_id", "uid", "uniqueid", "fmuid", "staffid");
        put(aliases, "age", "age");
        put(aliases, "nationality", "nationality", "nat", "nation", "country");
        put(aliases, "club", "club", "team", "currentclub", "employer");
        put(aliases, "division", "division", "league", "competition");
        put(aliases, "job", "job", "role", "position", "jobtitle");
        put(aliases, "date_of_birth", "dob", "dateofbirth", "born", "birthdate");
        put(aliases, "salary_weekly_raw", "wage", "salary", "wages", "weeklywage");
        put(aliases, "contract_end_date", "expires", "contractexpires", "contractend", "contractenddate", "expiry");
        put(aliases, "gender", "gender", "sex");
        put(aliases, "ca", "ca", "currentability");
        put(aliases, "pa", "pa", "potentialability");
        put(aliases, "current_reputation", "reputation", "currentreputation");
        put(aliases, "home_reputation", "homereputation");
        put(aliases, "world_reputation", "worldreputation");
        return Map.copyOf(aliases);
    }

    private static Map<String, String> staffAttributeAliases() {
        Map<String, String> aliases = new HashMap<>();
        for (StaffAttributeDefinitions.StaffAttribute attribute : StaffAttributeDefinitions.ALL) {
            aliases.put(normalize(attribute.key()), attribute.key());
            aliases.put(normalize(attribute.label()), attribute.key());
        }
        put(aliases, "people_management", "manmanagement", "manmanagment", "peoplemanagement");
        put(aliases, "working_with_youngsters", "wwy", "youngsters", "workingwithyoungsters");
        put(aliases, "judging_player_ability", "jpa", "judgingability");
        put(aliases, "judging_player_potential", "jpp", "judgingpotential");
        put(aliases, "judging_staff_ability", "jsa");
        put(aliases, "tactical_knowledge", "tacknowledge", "tacticalknowledge");
        put(aliases, "sports_science", "sportsscience", "sportscience");
        put(aliases, "data_analysis", "dataanalysis", "analytics");
        put(aliases, "set_pieces", "setpieces", "setpiece");
        put(aliases, "physiotherapy", "physio", "physiotherapy");
        put(aliases, "determination", "det");
        put(aliases, "motivating", "mot", "motivation");
        put(aliases, "negotiating", "neg", "negotiation");
        put(aliases, "goalkeeping", "gkcoaching", "goalkeepingcoaching");
        put(aliases, "possession", "poss");
        return Map.copyOf(aliases);
    }

    public enum Kind { PLAYER, STAFF }

    private static void put(Map<String, String> aliases, String value, String... keys) {
        for (String key : keys) {
            aliases.put(key, value);
        }
    }
}
