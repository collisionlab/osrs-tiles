# Published map assets

This repository publishes two kinds of release to `https://tiles.collisionlab.world`. Each is
immutable once uploaded, and there is no "latest": consumers pin exact versions.

- **Tiles** are the map imagery: every plane, in two styles. A new version is built only when the
  game cache or the renderer changes.
- **Inset bundles** are metadata only. They record which areas lie off the main map, where they are
  entered from, and what they are called. A new bundle is built whenever transports or names change.
  A bundle is cut from one tile version and draws all its pixels from it.

How these releases reach production (pin PRs, drafts, deploys) is in osrs-planner's
`docs/deployment.md`.

## Tiles: `/<style>/<version>/`

| Part | Value |
|---|---|
| version | `<openrs2 cache id>-<renderer commit, 12 hex>`. The commit is the last one touching renderer sources (not `InsetTopology`), the Gradle files or the flake |
| styles | `standard` (RuneLite's rendering, as in Explv's tiles) and `tactical` (no icons) |
| layout | `explv-tms-v1`: 256 px tiles, TMS rows counted from the south. Tile (c, r) at zoom 8 is map square (15 + c, 19 + r) at 4 px per game tile. Zooms 7–3 average 2×2 tiles of the zoom above. Nothing above 8: viewers upscale |
| plane 0 | `<style>/<version>/{z}/{x}/{y}.png`, the same addresses as Explv's set, so Leaflet uses it unchanged |
| planes 1–3 | `<style>/<version>/p{plane}/{z}/{x}/{y}.png` |
| `manifest.json` | cache and key hashes, renderer commit, `extent`, `planes[i]` = `{path, mapSquares, tiles}`, `missingKeyRegions`. Uploaded last, so a version with a manifest is complete |
| `index.json` | sha256 of every tile, keyed by path relative to the style directory (all planes) |
| `xteas.json` | the keys the version was rendered with, so inset builds reproduce it |

A tile URL for a world square `(x, y, plane)` at zoom 8:

```text
{base}/{style}/{version}/{plane ? `p${plane}/` : ""}8/{(x >> 6) - 15}/{(y >> 6) - 19}.png
```

**Which squares get a tile.** The extent is every map square in the cache (currently squares
15–65 × 19–196). On each plane a square gets a tile when it has visible terrain there, which is the
rule inset areas are cut by (`InsetTopology.visibleTerrain`). Some of those tiles render all black;
they're kept so every inset area has its tiles. Plane 0 also gets a tile for every other square in the
cache, unless it renders all black. **A missing tile means black.** Squares the cache lacks are
never rendered, and an upper plane renders only the 16×16-square blocks that have terrain on it.

**Size.** Cache 2740, per style:

| Plane | Map squares | Tiles, zooms 3–8 |
|---|---:|---:|
| 0 | 2,723 | 4,120 |
| 1 | 942 | 1,844 |
| 2 | 478 | 1,076 |
| 3 | 226 | 577 |

In CI (publish run for `2740-07d92fc06f50`), each style renders plane 0 in 65–90 s and all four
planes in 195–210 s, so about 7 minutes for both. Uploading takes about 5.5 minutes, and the whole
publish about 14. A style is about 55–65 MB, and its upper planes about 16 MB of that. Rendering an
upper plane in smaller blocks is slower: each render has a fixed cost that outweighs the area saved.

**Built by** `.github/workflows/publish.yml`: Thursdays at 06:00 UTC, after Wednesday's game update;
on renderer pushes to `main`; or by hand with an optional cache id. For a new version it:

1. re-renders `examples/lumbridge` and requires every hash to match (`scripts/check-golden`);
2. renders both styles (`gradle tiles`, `Tiles.java`);
3. compares them with the planner's pinned version (`scripts/compare-version`). Newly missing
   decryption keys fail the run;
4. uploads each style with its manifest last (`scripts/upload`);
5. hands the version to the `insets` workflow as the tile candidate.

## Inset bundles: `/insets/<version>/`

An inset is an area the planner shows in its own panel, beside where a route enters it, because it
lies off the main map. Examples are a dungeon 6,400 tiles north of its entrance, an instance in empty
space, or a small upper floor. A bundle describes those areas and has no imagery of its own: the
planner cuts each area's pixels from the pinned tiles of its plane.

| File | Contents | Read by |
|---|---|---|
| `manifest.json` | `schemaVersion` 2; `version`, `tileVersion` (the tiles it was built and checked against), cache hash, `files` (sha256 of each file below), `planes` (the map squares the areas cover, per plane), `inputs`, `componentCount`, `reviewCount` (the issues in `review.json`). Uploaded last | planner (checks it matches the pinned tiles, and the hashes); `compare-insets`; pin scripts |
| `regions.json` | every connected area of visible terrain, per plane: `id`, `plane`, `bounds`, `mask` (rectangles), `classification` (`inset`, `main-world`, `mixed`), `tileCount`; and `surfaceRects`, the main map's frame | planner: which area each route tile is in, clipping and outlining insets, which tiles to cut, placement and click hit-testing |
| `labels.json` | `regions[id]` = `{name, plane, status, areas}` (status `named`, `descriptive` or `unresolved`) and `evidence` for each name | planner: inset headers, door markers and the AREA search (only labels that aren't `unresolved`) |
| `anchors.json` | `connections` (transport rows matched to areas, and curated anchors); `regions[id].anchors` and `defaultAnchor` (where the area hangs off the main map: a surface point and the chain of connections from it); `reachableRegionIds` (areas some chain of transports reaches); `curatedIssues` | planner, fetched when first needed: placement, entrances and door markers. The labeller and the pin scripts use the rest |
| `review.json` | reachable areas without a name or an anchor, and curated decisions that no longer fit the terrain; `requiredNamed`, `requiredAnchored` | `pin-insets` (the pin PR's "Action needed"), the planner's `scripts/pin-status` |

The version is a hash of every input: cache and key hashes, tile version and manifest, the pinned
plugin's transports, this repository's sources, `data/inset-labels/`, and the build files. The
rules for names and anchors are below.

**Built by** `.github/workflows/insets.yml`:
- after each publish;
- on pushes to `main` touching `scripts/`, `data/inset-labels/` or `InsetTopology.java`;
- daily at 07:30 UTC, which picks up transport changes from the planner's tooling bump.

The build (`scripts/build-insets`) extracts terrain from the cache, finds the areas, matches
transports, compiles names, and then **checks that every style of the tile version has a tile for
every map square an area covers**. A tile version from before all planes were rendered is rejected.

## Names and anchors

**Every reachable area needs a name and an anchor.** Reachable means some chain of transport rows
leads there, following each row's own direction and counting every file, leagues and teleports
included, from the main world or from a row with no source, such as a spell. The name is what the
planner's area search lists. The anchor is where the planner places the area: beside a point on the
main map, linked by a line, instead of above the map. A reachable area without either is an issue in
`review.json`, which keeps the pin PR a draft (osrs-planner's `docs/deployment.md`).

Names come only from `data/inset-labels/annotations.json`, each tied to evidence in `sources.json`
or to a connection; `scripts/label_inset_regions.py --worklist` suggests evidence. Anchors are worked
out by `scripts/anchor_inset_regions.py`, preferring, in order:

1. **Physical connections**: ladders, stairs, doors and agility shortcuts in `transports.tsv` and
   `agility_shortcuts.tsv`, recognised by their action. They count in both directions, since a
   ladder up out of an area places it as well as one down into it.
2. **Curated anchors**, for areas no transport places. Areas entered by digging, through a fairy
   ring, through floors with no terrain, or through an entrance missing from the transport data.
3. **Any other transport with a source tile**: a lever, a portal, an NPC such as the Light creature
   into the Chasm of Tears. These count only in their own direction. Seasonal rows never anchor, nor
   do network stations (a source tile with more than two destinations in one file, such as a fairy
   ring or a spirit tree).

Among anchors of the same kind, fewer hops win; an anchor through a non-physical connection is used
only when no physical or curated one exists. A transport endpoint off every area's terrain (stairs
often land a tile or two off the floor they lead to) joins the only area with terrain within two
tiles, and stays unmatched when there are none or several.

To add a curated anchor, give the area's component an `anchor` in `annotations.json`:

```json
"anchor": {"point": [3654, 3519, 0], "reason": "The Ectofuntus trapdoor leads down to it, ..."}
```

`point` is on the main map, or inside another area, which the anchor then extends: the Viyeldi
caves start in the Shaman Caves, so they hang off the Shaman Caves' entrance in the Kharazi Jungle.
Areas reached only by fairy ring start in Zanaris. An anchor whose point isn't on the main map or
in another area, or whose seed no longer lies in its component, is a `review.json` issue.

## Who pins what

| Consumer | `tiles.json` | Moves when |
|---|---|---|
| osrs-planner | `{base, version, styles, insets: {version, tileVersion}}` | a new tile version or a new bundle |
| osrs-coord-preview | `{base, version, styles}` | a new tile version only: it reads every plane from the tiles and nothing from bundles |

`scripts/pin-insets` opens and refreshes the `pin/map-assets` PR in each repository. It's a draft
only while something needs a person, and its body says what (osrs-planner's `docs/deployment.md`).
coord-preview's PR waits only for big tile changes.

## Known quirks

- 264 squares on plane 1, 70 on plane 2 and 83 on plane 3 have visible terrain but render all black.
  Their insets show black there, as they did before the tiles covered upper planes.
- The planner's Leaflet map with the standard style still loads Explv's own tiles from GitHub. These
  `standard` tiles are used by insets, coord-preview and the planner's inset map.
