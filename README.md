# fm-csv-loader

A CSV snapshot loader for [fm-ai-assistent](https://github.com/JelmerBouma1985/fm-ai-assistent), so the app can be driven from FM view exports instead of by reading Football Manager's memory.

## Why

fm-ai-assistent reads FM26 data out of the running process. `ProcessReaders.open` branches on Windows and Linux only, and `FmOffsets` is indexed against `game_plugin.dll` — a Windows PE, which is what runs under Proton too. On macOS the app builds, boots and serves its whole UI, then fails the moment you press **Load data**:

```
Load failed: Unsupported operating system: Mac OS X
```

The rest of the app never touches memory. `FmAiAssistentTools` and `FmDecisionTools` have no imports from `memory`, `linux` or `windows` — all twenty MCP tools read the snapshot out of H2. So the snapshot can be filled from somewhere else, and everything downstream carries on unchanged.

This fills it from CSV views exported out of FM (the [FM26 Player Export](https://github.com/DadMych/fm26-player-export-macos) plugin runs on Apple Silicon), persisted through the same `SnapshotDatabaseWriter` the RAM load uses.

## Applying it

This repo holds the new files plus a patch against upstream. It is not a standalone application — it needs the upstream project to build.

```bash
git clone https://github.com/JelmerBouma1985/fm-ai-assistent.git
cd fm-ai-assistent
git apply /path/to/fm-csv-loader.patch
mvn -DskipTests package
java -jar target/fm-ai-assistent-*.jar
```

The patch adds the eight `src/main` files and the test kept here, and makes four edits to existing code: registering the new tool in `FmAiAssistentMcpConfiguration`, a *Load CSV* button in `MainView`, the tool-count assertion in `McpProtocolCompatibilityTest`, and the `inRange` fix described below.

## Using it

**In the UI** — *Load CSV* beside *Load data*. Upload a players export, a staff export, or both, with an optional in-game date and managed club.

**Over MCP** — `fm26_load_csv`:

```json
{"path": "squad.csv", "staffPath": "staff.csv", "gameDate": "2026-08-01"}
→ {"players": 10, "staff": 8, "clubs": 2,
   "players_detail": {"mapped_attributes": 37, "missing_columns": ["ca", "pa"]},
   "staff_detail":   {"mapped_attributes": 21, "ignored_columns": []}}
```

Any view works as long as it has a Name column. `examples/` holds a players and a staff export in the expected shape.

## What it handles

- **Delimiters** — `,` `;` tab `|`, sniffed per file, because the export follows the host locale
- **Money** — `£14K p/w`, `€2.5M`, `£11.5M - £14M` (midpoint), `Free` → 0, `Not for Sale` → unknown
- **Column names** — FM's abbreviations (`Cmp`, `OtB`, `1v1`, `TRO`, `JPA`, `WWY`) and its full attribute names
- **`Nat`** — FM uses it for both Nationality and Natural Fitness; a column reading as 1-20 ratings is the attribute, otherwise the nation
- **Positions** — `D/WB (RL), DM` expands into the fourteen positional ratings
- **Scouting ranges** — `12-15` is stored as 13 and the row is counted in `rows_with_estimated_attributes`
- **Unknown cells** — left null rather than defaulted, so an unpriced player never becomes free

## Limits

`ca` and `pa` are not in any FM view, so tools ranking on raw ability fall back to attribute-derived scoring. Clubs are name-only stubs — no finances, facilities or competition. Competitions are not loaded at all. Managed-club detection needs memory, so squad tools want an explicit `managingClub`. Staff coaching stars need every weighted attribute present, which means a staff view carrying all 21 coaching attributes — `StaffRoleRatingCalculator` returns empty otherwise.

## Bug fix carried in the patch

`FmAiAssistentTools.inRange` required a non-null value even when both bounds were null:

```java
return value != null && (min == null || value >= min) && (max == null || value <= max);
```

An absent bound is not a constraint, so an unfiltered `fm26_find_players` dropped every player with a null `ca`, `pa` or reputation. Invisible on the RAM path, where those are never null. `PlayerDatabaseService` and `ClubDatabaseService` already short-circuit to `true` when both bounds are null; this matches them.

## Testing

15 tests in `CsvSnapshotLoaderTest`. With the patch applied the upstream suite runs 144 tests, 0 failures.

Verified on macOS 15 (Apple Silicon, JDK 25) against hand-built exports, loaded through both the tool and the UI, then queried with `fm26_find_players`, `fm26_analyze_squad`, `fm26_compare_players`, `fm26_find_staff` and `fm26_create_shortlist_file` — which wrote a valid importable `.fmf`.

## Licence

MIT, see [LICENSE](LICENSE). This covers the code in this repository only.

fm-ai-assistent itself publishes no licence, which means all rights reserved by default. This repo therefore contains no upstream source — only additions and a patch that applies to a clone you make yourself.
