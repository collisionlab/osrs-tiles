# map-style: cartographic rendering experiment

A small, standalone renderer for trying alternative looks for the OSRS world
map. It renders a few map squares straight from a game cache, once per style,
so each change can be compared against the standard RuneLite rendering in
seconds instead of a full-world render plus tile pyramid.

Branch `experiment/map-style`. Local only. Nothing here touches the plugin,
the pathfinder, the web viewer or any production tile assets.

## Render a region

```bash
# a cache + keys.json from the existing tooling flow (python3 scripts/maintenance.py cache)
T=../../shortest-path-tooling        # or wherever ./cache and keys.json live

./gradlew -p map-style run --args="--cache $T/cache --xteas $T/keys.json \
    --regions 12850,12851,13106,13107 --plane 0 \
    --styles runelite,standard,declutter,muted,relief --height \
    --out build/map-style/lumbridge"
```

| Flag | Meaning |
|------|---------|
| `--cache DIR` | OSRS disk cache (`main_file_cache.*`) |
| `--xteas FILE` | RuneLite-format keys (`[{"region":…,"keys":[…]}]`). `[]` is valid (see below). |
| `--area X0,Y0,X1,Y1` | inclusive world-tile rectangle, **or** |
| `--regions ID,…` | map squares (`(x>>6)<<8 \| (y>>6)`); renders their union |
| `--plane Z` | 0–3 |
| `--styles S,…` | `runelite` (default), `standard`, `declutter`, `muted`, `relief` |
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
  │    StyledMapImageDumper (fork)      styles standard, muted, relief
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
`MapImageDumper` with a `MapStyle` hook at each colour decision. Each hook
gets the colour RuneLite would draw plus the decoded data behind it
(definition, world x/y/plane, and a `Terrain` height lookup over every
loaded square). The fork is small: 39 lines added, 71 removed, and the
removals are the old `main()`. Its default behaviour
is verified identical, so every visual difference is attributable to a
hook. `muted` exercises all three colour hooks (underlay, overlay,
wall/door). It is a proof of control, not a proposed style.

`compare.png` panels, left to right: `runelite`, `standard`, `declutter`,
`muted`, `relief`, `height` (white = highest; Lumbridge spans 520 height
units ≈ 4 tiles).

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
