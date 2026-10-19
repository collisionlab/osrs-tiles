# map-style: cartographic rendering experiment

A small, standalone renderer for trying alternative looks for the OSRS world
map. It renders a few map squares straight from a game cache, once per style,
so each change can be compared against the standard RuneLite rendering in
seconds instead of a full-world render plus tile pyramid.

Status and history of the styles are in [HANDOFF.md](HANDOFF.md).

## Published tiles

`Tiles` writes the whole map in the route planner's (Explv's) layout, and
the `publish` workflow serves it from `tiles.collisionlab.world`:

```text
https://tiles.collisionlab.world/<style>/<version>/{z}/{x}/{y}.png    z3-8, TMS rows
https://tiles.collisionlab.world/<style>/<version>/manifest.json      cache, renderer, extent, missing keys
https://tiles.collisionlab.world/<style>/<version>/index.json         sha256 per tile
```

Styles are `standard` (RuneLite's rendering, matching Explv's tiles) and
`tactical` (no icons). A version is `<openrs2 cache id>-<renderer commit>`,
where the renderer commit is the last one touching renderer sources (excluding
the inset extractor), the Gradle files
or the flake. Versions never change once uploaded. There is no "latest":
osrs-planner's `tiles.json` pins the version it shows.

The workflow runs on Thursdays (after Wednesday's game update), on renderer
changes to `main`, and by hand with an optional cache id. When the version
is new it:

1. re-renders `examples/lumbridge` from openrs2 cache 2727 and requires
   every hash to match (`scripts/check-golden`, also run on every PR);
2. renders both styles from the cache;
3. compares them with the pinned version (`scripts/compare-version`): newly
   missing decryption keys fail the run, and more than 10% of a style's tiles
   changed marks the pin for review;
4. uploads with rclone, each style's `manifest.json` last
   (`scripts/upload`);
5. records a tile candidate artifact. The `insets` sidecar builds compatible
   metadata and opens one tile/inset pin PR in osrs-planner. Routine validated
   pairs merge after planner checks; the initial pair, substantial changes and
   reachable areas without names wait in draft for review.

Change a style's pixels on purpose and you regenerate the examples in the
same commit, or the golden check fails.

## Render a region

```bash
# a cache + keys.json from the existing tooling flow (python3 scripts/maintenance.py cache)
T=../../shortest-path-tooling        # or wherever ./cache and keys.json live

./gradlew -p map-style run --args="--cache $T/cache --xteas $T/keys.json \
    --regions 12850,12851,13106,13107 --plane 0 \
    --styles runelite,standard,declutter,muted,relief,tactical --height \
    --out build/map-style/lumbridge"
```

| Flag | Meaning |
|------|---------|
| `--cache DIR` | OSRS disk cache (`main_file_cache.*`) |
| `--xteas FILE` | RuneLite-format keys (`[{"region":…,"keys":[…]}]`). `[]` is valid (see below). |
| `--area X0,Y0,X1,Y1` | inclusive world-tile rectangle, **or** |
| `--regions ID,…` | map squares (`(x>>6)<<8 \| (y>>6)`); renders their union |
| `--plane Z` | 0–3 |
| `--styles S,…` | `runelite` (default), `standard`, `declutter`, `muted`, `relief`, `tactical`, `tactical-dusk`, `tactical-field`, `tactical-classic` |
| `--height` | also write `height.png` |
| `--labels` | draw area labels (off by default, as in Explv's tiles) |
| `--out DIR` | output directory, created if missing |

Output: `<style>.png` at 4 px per tile (RuneLite's scale), `height.png`,
`compare.png` (every panel side by side) and `manifest.json` (canonical cache
and key paths, SHA-256 of `main_file_cache.idx255` and of the key file, the
RuneLite cache version, the area, the plane, the map squares loaded, and the
SHA-256 of every output). A 2×2 map-square render takes about 6 s end to end,
including Gradle, with each style taking about 1 s.

Reproducibility: re-running the command above produces byte-identical PNGs.
`standard.png` and `runelite.png` have the same hash, so the fork with
default hooks renders exactly what RuneLite renders.

`examples/lumbridge/` holds the output of the command above (cache
`idx255` sha256 `e02780d1…`, which is most likely openrs2 cache 2727,
2026-09-30, build 241). `examples/varlamore/` is the same set of styles over
a larger, hillier area: Civitas illa Fortis, the Hunter Guild and the Sunset
Coast (`--area 1472,2944,1791,3199`, 320 × 256 tiles, heights −1736…0).
Relief reads much more clearly there than at Lumbridge, and Varlamore shows
the relief style's limits: faint per-tile height noise in the southern
grassland, and shallow coastal water darkened by the cliff slope beside it.

### The iteration loop

1. Pick an area (`--area` or `--regions`).
2. Render `runelite` together with your style.
3. Edit a hook in `Styles.java` (or add a style).
4. Re-run the same command (Gradle recompiles only what changed).
5. Open `compare.png`, or diff the hashes in `manifest.json`.

## How it works

```
RenderRegions (CLI)
  ├─ Store + XteaKeyManager             RuneLite cache, unchanged
  ├─ RegionLoader.loadRegionFromArchive  only the requested squares + 1 ring
  ├─ MapStyle.keep → Region.getLocations().removeIf   data-level object filter
  ├─ renderer:
  │    MapImageDumper (unforked)        styles runelite, declutter
  │    StyledMapImageDumper (fork)      styles standard, muted, relief, tactical
  │      .drawMap(plane)                over the loaded squares' bounds
  └─ crop to the requested area → PNG + manifest
```

The one-square margin matters: RuneLite blends underlay colours over a
5-tile window and draws objects that overhang from neighbouring squares, so
without the margin the edges of a small render would differ from the same
tiles in a full-world render. The margin is cropped away afterwards.

`RegionLoader.loadRegions()` returns early when regions are already loaded,
so pre-populating a loader and passing it to `new MapImageDumper(store,
loader)` restricts RuneLite's renderer to a subset **without forking it**.
`drawMap(z)` then renders the bounding box of whatever was loaded.

## Upstream pipeline (Explv/osrs_map_tiles)

Inspected at `1937ccf` (2026-10-08).

| Stage | Where | What it does |
|-------|-------|--------------|
| Cache acquisition | `tile_generator.py` `download_cache_with_xteas` | Scrapes the archive.openrs2.org HTML table, takes the newest OSRS cache, downloads `disk.zip` and `keys.json`, and renames `mapsquare`/`key` to `region`/`keys` (the same patch as our `maintenance.py`). |
| World rendering | `java/.../Main.java` | About 90 lines. It wraps **`net.runelite.cache.MapImageDumper`**, exposing its seven boolean setters (`renderMap`, `renderObjects`, `renderIcons`, `renderWalls`, `renderOverlays`, `renderLabels`, `transparency`) as CLI flags, and writes `img-<plane>.png` for all four planes of the **entire world**. Nothing in it renders; all drawing is RuneLite's. |
| Build | `java/build.gradle.kts` | Shadow jar on `net.runelite:cache:<latest from static.runelite.net/bootstrap.json>`, so the version floats on every build. |
| Tile pyramid | `tile_generator.py` `generate_tiles_for_plane` | pyvips. It diffs the new full image against the previous one per 256-px tile (SSIM < 0.999), writes changed tiles at the native zoom, splits them upward with nearest-neighbour scaling to z11 and joins them downward with lanczos3 to z3, giving `<plane>/<zoom>/<x>/<y>.png`. |
| Packaging | `Dockerfile` | python:3.10 + miniconda + a py3.7 env with pyvips, OpenJDK 11 and Maven. It builds the jar and runs the python script against `/repo`. It is run with `docker build ./tile_generator` then `docker run -v $(pwd):/repo`, with Docker set to 8 GB of memory. |

**What we reuse:** the architecture (RuneLite's `MapImageDumper` as the
renderer, openrs2 cache + keys) and our *existing* copy of the acquisition
step (`collision-map-update/download-latest-cache.sh` via `maintenance.py
cache`), so there is no second download pipeline.

**What we changed:** subset loading (map squares + margin instead of the
whole world), an explicit plane, an explicit output directory, a pinned
RuneLite version, and a manifest.

**What we left out for now:** the Docker/conda image, full-world rendering,
and the SSIM-incremental tile pyramid. When we need tiles, the pyramid step is
a small, generic image operation. Re-implement it independently (pyvips
`dzsave --layout google` does split + join in one call) rather than copying
`tile_generator.py`.

## Licensing and compatibility

- **Explv/osrs_map_tiles has no licence**: no LICENSE file, and the GitHub
  API reports `license: null`. Under default copyright that means no
  permission to copy. **No upstream code is copied or vendored here.** Only
  the architecture is reused, and that is just "call RuneLite's dumper".
  If we ever want their tiles or scripts, ask Explv for a licence first.
- **RuneLite is BSD-2-Clause.** `StyledMapImageDumper.java` is a fork of
  `MapImageDumper.java` at tag `runelite-parent-1.13.2`. It keeps the
  original copyright notice and states its changes in the header, which is
  all the licence requires. Map imagery rendered from the cache is Jagex
  content. Keep it out of anything public unless that is cleared separately.
- **Version pinning.** The fork must match the `net.runelite:cache` it
  links against, so this build pins `1.13.2`. The tooling's own
  `latest.release` is unaffected. When bumping, re-diff the fork against
  upstream `MapImageDumper` and confirm `runelite.png == standard.png` again.
- **XTEAs.** openrs2 reports zero keys for recent OSRS caches, and the
  tooling's `keys.json` is `[]`, but locations still decode. Current map
  squares are evidently not encrypted. Older caches will need their real
  key set. `--xteas` is required so the key set used is always recorded.
- openrs2 disk caches trigger thousands of harmless "revision mismatch"
  warnings in RuneLite's `Archive`. `build.gradle` silences that one logger.

## Where custom cartography goes

What RuneLite's renderer exposes without changes:

| Property | Public API? | Notes |
|----------|-------------|-------|
| Layer on/off (terrain, overlays, objects, walls, icons, labels) | ✅ setters | All Explv uses. |
| Which objects appear | ✅ data level | `Region.getLocations()` is the live list, so filtering it before drawing works with the unforked renderer (`declutter`). |
| Area subset | ✅ | Inject a pre-populated `RegionLoader`. |
| Underlay / overlay **definitions** (colour, texture, secondary colour) | read-only | `UnderlayDefinition`/`OverlayDefinition` load via public loaders, but the dumper keeps its own private copies and colour logic. |
| Terrain heights | ✅ read | `Region.getTileHeight`. The dumper never uses heights. |
| Overlay shape + rotation | ✅ read | `Region.getOverlayPath/Rotation`. The shape rasters (`TILE_SHAPE_2D`) are private. |
| Tile settings (bridges, hidden tiles) | ✅ read | `Region.getTileSetting`. |
| Walls and map objects | ✅ read | `Location` type/orientation + `ObjectDefinition` (`mapSceneID`, `wallOrDoor`, `mapAreaId`). |
| Textures | ✅ read | `TextureManager`/`RSTextureProvider.getAverageTextureRGB`. |
| Terrain/overlay/wall **colours**, blending, shape rasterisation, sprite drawing | ❌ | All private in `MapImageDumper`. The only override, `protected random()`, shifts wall colour by about ±10. |

So data selection can stay on stock RuneLite, but **any change to how
something is drawn needs the fork**. That is the extension point: one fork of
`MapImageDumper` with a `MapStyle` hook at each colour decision, plus one
for map icons. Each colour hook
gets the colour RuneLite would draw plus the decoded data behind it
(definition, world x/y/plane, and a `Terrain` view of every loaded square:
heights, plus RuneLite's overlay colour for any tile). The fork's diff
against upstream is +95/−105: the old `main()` is gone, the hooks are in,
and the overlay-colour code moved verbatim into its own method so `Terrain`
can reuse it. Its default behaviour
is verified identical, so every visual difference is attributable to a
hook. `muted` exercises all three colour hooks (underlay, overlay,
wall/door). It is a proof of control, not a proposed style.

`compare.png` panels, left to right: `runelite`, `standard`, `declutter`,
`muted`, `relief`, `tactical`, `height` (white = highest; Lumbridge spans 520 height
units ≈ 4 tiles).

## The tactical style

`tactical` (`TacticalStyle.java`) is the first style aimed at the web
planner's look: its dark olive UI, pixel font and `image-rendering:
pixelated` map, in the spirit of Advance Wars. It works entirely through
the hooks:

- **Ground:** RuneLite's *blended* ground colour is snapped to flat
  terrain classes: three grasses, two olive scrubs, sand, snow, two dirts,
  rock, ash (dark, nearly colourless ground, i.e. the Wilderness) and two
  swamps (teal-green ground, i.e. Morytania). Bright orange-yellow overlays
  become lava, so the Wilderness reads as dangerous at a glance.
  The class boundaries come from hue/saturation/brightness histograms of
  RuneLite renders of Lumbridge, Varlamore, the Wilderness, Morytania and
  Trollheim. One case colour cannot settle: the Kharidian Desert and
  Varlamore's olive grass blend to the same colour (about hue 0.13,
  saturation 0.8, value 0.6). Their ground definitions differ, though: sand
  definitions are bright (≥ 0.55), the grass is not. So a scrub or dirt tile
  becomes sand when at least three quarters of the ground definitions in
  its 5×5 window are sandy (`Terrain.underlayDefinition`). Snapping the blend gives smooth regions
  with tile-stepped edges. Classifying each tile's own underlay
  definition was tried first and failed: definition colours don't match
  what RuneLite displays (Al Kharid's desert came out green), and the raw
  data alternates ground types tile by tile (Varlamore turned into a
  checkerboard).
- **Elevation:** 192-unit (1.5-tile) terraces, with a dark cliff line on
  tiles whose north or west neighbour is a step higher, plus a three-level
  quantised hillshade.
- **Water:** banded by squared distance to the nearest land tile, using
  `Terrain.overlay` to see neighbouring tiles up to 4 tiles away. The bands
  are a pale foam line (≤ 1.4 tiles), two shallow bands, then deep teal with
  sparse two-tile wave dashes. Rivers come out as foam banks around a
  shallow channel. On the land side, any tile that is partly water or has
  water on one of its four sides is darkened (`coastShade`), so every
  coast and river bank also has a land-side edge. A tile counts as water by its displayed colour or by
  definition (a blue or teal base colour on a textured or two-colour
  overlay; textured ones may be as grey as 8% saturation). The definition
  test catches Sailing's regional seas, whose textures average outside the
  blue window, and the near-grey fetid sea west of the Feldip Hills
  (textures 175-179). Sailing also textures those seas with thousands
  of nameless decoration objects (map-scene sprites ≥ 201, which sit almost
  only on water across the whole surface). The tactical style drops them via
  `keep`, so every sea gets the same bands and the open-ocean rectangles in
  the whole-world render are gone.
- **Seas:** the cache has about 40 water texture families, roughly one per
  sea. Each water tile measures its sea's definition colour (averaged over
  the 9×9 shoreline window) against ordinary ocean (texture 133, `#4a5d8a`)
  and shifts the palette's bands by the difference: hue by the offset,
  saturation and brightness by the ratio, with brighter seas also losing
  saturation. A dead zone keeps ordinary ocean exactly on the palette, and
  the shifts are stepped so each sea is a flat region. This yields a dark
  deep-ocean frame at the world's edge (texture 208), a green-teal
  south-west Sailing sea, indigo seas off Morytania and in the south-east,
  and a murky grey fetid sea. The frozen sea in the north (textures
  160-164, identified by location) is too close to ordinary ocean to stand
  out, so it is measured against a fixed pale ice colour instead, applied at
  30% strength (`FROZEN_TINT_STRENGTH`; at full strength it reads as white).
  The strength is blended by the window's share of frozen tiles, so the
  frozen sea's edge does not step.
- **Surfaces:** roads and paths as light stone or tan.
- **Buildings:** any tile under a roof (tile setting bit `0x4`, which the
  client uses to hide roofs, exposed as `Terrain.roofed`) is a building
  floor rather than terrain. Grey floors become flagstones and brown floors
  planks, each in a light per-palette colour textured per tile in a few
  close tones (staggered two-tile flagstones, staggered four-tile planks).
  Coloured floors keep a muted version of their hue. Floor tiles along the
  north and west walls are lit, and ground just south and east of a building
  is shadowed, so buildings read as raised blocks. No relief or coast rim
  applies indoors. Roofless rooms and yards are not flagged and stay
  terrain.
- **Walls and doors:** walls in the planner's `--ink` colour, doors in its
  `--orange`.
- **Icons** (`TacticalIcons.java`, through the `icon` hook): only icons that
  matter for route planning are kept, redrawn as 11-px pixel badges in the
  style of the planner's square bank marker (ink fill, accent border, 7×7
  glyph). Cyan marks places the planner routes to (bank, altar, anvil,
  matching the plugin's destination TSVs); orange marks ways through the
  world (transportation, agility shortcut, docking point, dungeon entrance,
  dungeon/map link). Shops, tutors, training spots, water sources, quest
  starts and minigames are hidden. Area definitions carry no names, so icons
  are keyed by sprite id, identified against the OSRS Wiki world-map key and
  checked by position (e.g. the map link on the Lumbridge cellar trapdoor,
  the docking point at the Varlamore docks). The apothecary sprite is not
  mapped yet: neither example area has one.

### Variants

Each tactical variant is a `TacticalStyle.Palette`: plain data (terrain,
road, water and wall colours, plus the relief settings), so a new variant
needs no new drawing code.

| Style | Look |
|-------|------|
| `tactical` | the planner's olive daylight look |
| `tactical-dusk` | the planner's dark theme: low-light olives, an ink-dark sea with a cyan foam line, muted walls |
| `tactical-field` | a printed field map: khaki and sage paper, brown contour lines on every terrace edge instead of cliff shadows, pale water with a blue shoreline, no waves or hillshade |
| `tactical-classic` | saturated Advance Wars colours: bright plains, blue sea, white surf, heavy cliff shadows |

Field's contours wobble in flat grassland, where height noise keeps
crossing terrace boundaries. Smoothing heights before contouring would fix
it.

Not done yet: RuneLite's object sprites still use RuneScape art: trees and
rocks (a real tactics look wants our own forest and mountain glyphs) and
in-building ladders and stairs, which matter for routing and need an
object-sprite hook. Quest starts may deserve a quieter third badge kind.
The palette has only been checked by eye against the planner.

## The whole world

The surface is one solid block of 48 × 34 map squares (tiles 960–4031 ×
2048–4223, Sailing's seas included). It renders in about 20 s with a 10 GB
heap, giving a 12288 × 8704 PNG (about 15 MB). `Pyramid` cuts that into a
Leaflet tile pyramid (1024-px tiles, zoom 0–4, 152 tiles, about 22 MB), and
`viewer/index.html` shows it with pixelated scaling and a world-tile
coordinate readout:

```bash
./gradlew -p map-style installDist
MAP_STYLE_OPTS=-Xmx10g map-style/build/install/map-style/bin/map-style \
    --cache $T/cache --xteas $T/keys.json --area 960,2048,4031,4223 \
    --plane 0 --styles tactical --out build/map-style/world
java -cp 'map-style/build/install/map-style/lib/*' mapstyle.Pyramid \
    build/map-style/world/tactical.png build/map-style/world/tiles
cp map-style/viewer/index.html build/map-style/world/
python3 -m http.server -d build/map-style/world 8000
```

The viewer hard-codes the render's size and origin (`FULL_W`, `FULL_H`,
`WORLD_X0`, `WORLD_Y1`). Change them if you render a different area.

## Recommended next step: terrain and relief

The data is there: `height.png` shows the river valley and the Lumbridge
plateau clearly. The renderer does not use it yet. In order:

1. **Height-aware hooks. Done.** Hooks receive a `Terrain`
   (`height(x, y, z)` over every loaded square). The `relief` style
   hillshades RuneLite's ground and overlay colours by tile slope: a Horn
   3×3 gradient, light from the NW at 45°, and 1.5× vertical exaggeration
   (all constants in `Styles.java`). Shading is applied per tile in the
   colour hooks, so it follows terrain type and leaves objects, walls and
   icons alone. Known limits: at 4 px per tile the shading is blocky
   (one value per tile), and the procedural height noise in flat ground
   still shows faintly. Both point to step 2. Exaggeration 3 with a plain
   central difference was tried first and turned the noise into speckle.
2. **Sub-tile terrain.** RuneLite fills each tile with one flat colour. For
   smooth relief, rasterise the ground pass at the vertex level: heights are
   per tile corner, so interpolate colour and shade across the 4×4 pixels.
   This replaces the fork's `drawMap(int[][] pixels, …)` method (about 250
   lines) with our own implementation behind the same hooks. Objects, walls
   and icons stay RuneLite's.
3. **Vector outputs for cartographic layers.** Water bodies (overlay ids with
   water colours or textures), coastlines and contours (marching squares on
   `getTileHeight`) are better derived as polygons from the same decoded
   data, then styled independently of the raster pass.
4. **Pyramid last.** Once a style is worth seeing at every zoom, add a
   full-world mode and a `dzsave`-style pyramid as an independent
   implementation, not a copy of Explv's script.

## Inset sidecar

The `insets` workflow is a build job, not a runtime server. It runs after tile
publication, daily to detect transport changes in the planner's pinned tooling,
and when inset code or curated labels change. `scripts/build-insets` extracts
cache terrain with `InsetTopology`, discovers exact connected masks, builds
entrance evidence and compiles reviewed names. Every inset component a player
can reach needs a name, because the planner's area search lists only named
areas. Reachable means that some chain of transport rows leads there, following
each row's own direction and counting every file, teleports included, starting
from the main world or from a row with no source, such as a spell
(`reachableRegionIds` in `anchors.json`). A changed component or missing name
uses a coordinate label and appears in `review.json`. Unknown or removed
evidence never supplies a name.

Native imagery uses the existing `explv-tms-v1` layout. Plane 0 reuses published
tiles; missing native squares and upper planes are rendered as supplements.
The renderer's `--squares FILE` takes a JSON array of packed map square IDs.
Extraction and supplemental renders are cached by their actual inputs, so a
label/transport-only build does not render terrain again.

An immutable inset release is served at `/insets/<version>/`: `regions.json`,
`labels.json`, `anchors.json`, `review.json`, optional `planes/<plane>/` tile
pyramids, and a manifest written last. Its identity includes cache/key hashes,
transports, generator sources, curated evidence and the compatible tile pin.
The planner pins `{version, tileVersion}` under `tiles.json.insets`. Runtime
never follows a latest pointer. Existing tile URLs stay unchanged.

`scripts/compare-insets` requests review for the first release or >10% change in
component count or covered map squares, and `scripts/tile-review` for more than
10% of a style's tiles changed (recomputed from the published indexes on every
run). Those, the first insets, and a reachable component without a name keep
the pin PR a **draft**, and its body lists each one under "Action needed" with
what to do: check and mark it ready, or for names, add them in
`data/inset-labels/annotations.json` (`scripts/label_inset_regions.py
--worklist` suggests evidence). A refresh with nothing left to check marks the
PR ready, so CI merges it. A PR a person marked ready stays ready while every
remaining reason is one it already listed (`review-keys` in the body).
While a pin PR is open, every run builds for its tiles, not `main`'s, so a
daily run never reverts a tile update.
`scripts/pin-insets` maintains a single bot-owned `pin/map-assets` branch and
uses an expected-head lease when updating it. Planner CI validates asset hashes
and coverage and matches the tested PR head before auto-merging.

Tests: `python3 -m unittest discover -s tests`, `nix develop -c gradle test`,
and `nix develop -c scripts/check-golden`. The full local build is:

```sh
nix develop -c scripts/build-insets --cache /path/to/cache --xteas /path/to/keys.json \
  --pin /path/to/osrs-planner/tiles.json \
  --transports /path/to/pinned-plugin/src/main/resources/transports \
  --commit "$(git rev-parse HEAD)" --output build/insets
```

Integration sources were preserved from tooling `experiment/map-insets` commit
`66b1bff`; the curated evidence remains build/test data rather than a runtime
network dependency. The full source snapshot is not uploaded by the publisher.
