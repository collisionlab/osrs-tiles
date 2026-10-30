# Production routing layers

These are an independent immutable sidecar, not a terrain style or a natural-component explorer.
They update by themselves, like the tiles: see [Automatic updates](#automatic-updates).
`routing-layers/plugin.json` pins the default plugin commit and the RuneLite client version;
`-ProutingPluginRevision=<sha>` builds another commit (the automatic build passes the planner's). The
Gradle subproject archives that exact commit from a checkout, compiles its original Java sources
and packages its original resources. No working-tree edits are included. The renderer's RuneLite
cache dependencies stay on a separate classpath. Neither shortest-path-tooling nor shortest-path-hs
is a generator dependency.

```sh
git clone https://github.com/Skretzo/shortest-path.git work/plugin
scripts/fetch-routing-collision https://tiles.collisionlab.world 2746-44385d655b71 work/collision
nix develop -c gradle :routing-layers:test routingLayers \
  -ProutingPluginDir="$PWD/work/plugin" \
  -ProutingCollisionDir=work/collision \
  -ProutingTileVersion=2746-44385d655b71 \
  -ProutingOutput=work/routing-layers
```

The default checkout is `../../shortest-path`, useful in the shared development workspace. If it is
absent, Gradle fetches only the pinned commit from the pinned GitHub repository into its build directory. The
explicit revision is always required by the checked-in pin, regardless of the checkout's HEAD.
Do not modify the plugin through this project.

Generation first checks the FNV-1a fingerprint of the **packaged collision-map.zip bytes** against
`RoutingCuts.read()`. It then splits the ZIP entries into the tile release's padded 2048-byte plane
files and compares them with the tile release's collision layer, whose published checksums must hold.
The graph is always built from the plugin's own map; the tile release only says where it is drawn.
The two are separate dumps: the plugin's weekly `ExtractCollisionMap` job runs tooling master on the
newest cache, and the tiles run the dumper osrs-tiles' `flake.lock` pins on their cache. So they can
differ in a few squares (a dumper fix, or a cache in between), and the manifest's
`collisionDifferences` lists the plane files that do. The tile version must match the collision
manifest's cache and renderer revision.

The graph comes directly from `RoutingStaticBuilder.build(collision, ResourceLoader.transports(),
ResourceLoader.destinations().get("bank"), RoutingCuts.read(bytes).pairs())`. Ownership is copied
from `searchTile(i)` / `routingComponent(i)`, preserving gaps and production IDs. Crossings come
only from the resulting `RoutingStatic`, including diagonal edges, endpoint coordinates and actual
costs. The raw/duplicate/invalid/non-separating cut counts are in the manifest. No raw cut is drawn
as an active crossing.

Separately, `RoutingStaticBuilder.walkingGraph()` classifies **all** collision-walkable tiles by
natural-component structural reachability. Only unreachable walkable tiles enter the negative
mask, and not those of an **open expanse**: an unreachable natural component of 100,000 tiles or more
(`RoutingLayers.EXPANSE_TILES`). The collision map leaves sea tiles open, because ships sail over
them, so each sea is one huge walkable component that no walking transport reaches. On cache 2746
the five surface seas are 249,286 to 2,458,003 tiles and the largest enclosed unreachable area is
35,933, so the threshold sits in a sevenfold gap. Expanses are neither counted nor drawn; the
manifest lists each one's plane, tile count and bounds so the exclusion can be checked. Search membership is checked against that classification. A reachable tile omitted by
production candidate discovery remains reachable, without a made-up routing ID. Cuts cannot
change the negative mask. Quest, item, skill and profile filtering are not applied.

## Files

The output directory is `<output>/<24-hex-content-address>/`:

- `manifest.json`: schema 3, plugin revision, tile release, ZIP hash, normalized collision-content
  hash, cut fingerprint, separator/transport/bank hashes, graph counts, `expanses` (the threshold, their tile count, and each one's plane, tiles and
  exclusive-max bounds), cut diagnostics, `componentPalette` (the component colours as hex RGB), all
  file hashes.
- `components.json`: retained production IDs, plane, search tile count, exclusive-max bounds,
  representative tile, incident crossing count, distinct neighbouring component IDs, and `colour`,
  the component's index in `componentPalette`.
- `components/[p<plane>/]<z>/<x>/<y>.png`: the components as a tile pyramid (below).
- `blocked/[p<plane>/]<z>/<x>/<y>.png`: blocked ground as a tile pyramid (below).
- `crossings.json`: the full graph's effective crossings, for offline analysis.
- `p<plane>/<world-square-x>/<world-square-y>.bin`: ownership, reachability and boundaries.
- The same square with `.crossings.json`: only its incident effective crossings, for lazy display.

The browser never downloads `crossings.json`. It loads metadata when selecting a component and
loads only visible squares and their crossing records. The same square is shared between every
inset placement. Crossings have stable IDs scoped by the content-addressed version (`version:id`).
`fromComponent` / `toComponent` are the production site memberships; if a future production build
retains a crossing entirely outside the search graph, an absent membership is omitted, never
invented. The current pinned build has no such crossings.

## Component tiles

The components are also published as a tile pyramid in the styles' layout (`explv-tms-v1`, see
`docs/tiles.md`): 256 px tiles with rows counted from the south, tile (c, r) at zoom 8 being map square
(15 + c, 19 + r) at 4 px per game tile, zooms 3–7 each half the one above, plane 0 at the root and
planes 1–3 under `p1/`–`p3/`. Viewers show the whole world's partition from these without fetching a
square, and paste inset terrain's tiles from them exactly as from a style's. Every search tile is
its component's `componentPalette` colour; everything else is transparent, and a tile with no search
tile isn't written (the manifest lists every tile there is). Colours never blend: a pixel covering
several game tiles at zooms 5–3 takes the commonest colour among them, so small components stay
visible. The PNGs are 4-bit paletted, about 1 KB each; cache 2746 has 2,359 of them, 11 MB in all.

Colours are chosen here, once, so every viewer agrees: largest component first, each takes the first
palette colour not used by a component it touches (shares a tile edge with) or crosses to, starting
from a place its id picks, so lone components vary too. The rule guarantees a component differs from
its neighbours whenever fewer than ten of them are coloured before it; on cache 2746 no two neighbours,
by edge or crossing, share a colour.

## Blocked tiles

Blocked ground is published as a second pyramid in the same layout, so a viewer can show collision
at any zoom without reading a square: every tile of a collision-bearing square that isn't walkable
(status 0) is red `#eb263a`, walkable ground transparent, and squares with no collision at all are
left out. Where a pixel covers several game tiles (zooms 7–3) its alpha says what share of those with
collision are blocked, in four steps (alpha 70, 105, 125, 140), so walkable regions still show through
from far out. The PNGs are 4-bit paletted, about 500 bytes each; cache 2746 has 8,326, 6.6 MB in all.

## Binary format (RTL1)

All integers are **little-endian**. A square covers 64 by 64 game tiles on one plane. World-square
coordinates are `worldX >> 6`, `worldY >> 6`; these are not the offset Explv image addresses.
Tile index is `(localY * 64 + localX)`, south row first, west to east. Boundaries lie at the edges
of unit tile squares, not at the tile centres used by crossing endpoints.

| Field | Bytes | Meaning |
|---|---:|---|
| Magic | 4 | ASCII `RTL1` |
| Palette count | 4 | Number of production IDs in this square |
| Incident crossing count | 4 | Number of crossing references below |
| Tile count | 4 | Always 4096 |
| Palette | 4 × count | Sorted nonnegative i32 production IDs, without renumbering |
| Tile records | 8 × 4096 | u16 palette ref; u8 classification; u8 boundary flags; u32 natural-component size |
| Crossing references | 4 × count | u32 indices/IDs in the version's crossing records |

Palette reference zero means **absent**; reference `n` selects palette entry `n-1`.
Classification zero is non-walkable, one structurally reachable walking terrain, two structurally
unreachable walking terrain, three walkable terrain in an open expanse (see above). Absent membership does not determine reachability. Files also cover
collision-bearing planes without walkable tiles. A square absent from the manifest has no published
collision plane; an HTTP/checksum failure on an indexed square is an error, never missing collision.

Two bits per tile side, N/E/S/W starting at the least significant pair: 0 means no boundary,
1 exterior of the retained search tiles, 2 another production routing component. These flags are
computed by global search-tile lookup, so square borders create no seams. Holes need no special
case. Neither boundary kind claims an artificial cut: the effective crossing records alone identify
synthetic separators, including diagonals which cannot be represented by a cardinal border.

The normalized content hash is SHA-256 over sorted entries: UTF-8 relative plane path, NUL, and the
32 raw bytes of the SHA-256 of its padded plane data. Transport content uses the same framing over
all resource-bearing production TransportTypes and their original resource paths/bytes. The
artifact version is the first 24 hex characters of SHA-256 of the compact manifest before its
`version` field is appended. File hashes address every payload, including component metadata.

## Automatic updates

The osrs-tiles `insets` workflow (daily, after each tiles publish, or dispatched) runs
`scripts/build-routing-layers` on the candidate `tiles.json` before it opens the planner's
`pin/map-assets` PR. When the pinned layers' `pluginRevision` isn't the planner's pinned plugin
(`tooling/shortest-path`), it:

1. compares that plugin's `collision-map.zip` with the collision layer of the candidate tiles and of the
   tiles the current layers use, and picks the one with fewer differing squares (the newer on a tie);
2. builds the layers for that plugin commit on those tiles, uploads them, and writes `routingLayers`
   into the candidate pin, which goes out in the same PR as the tiles and insets.

A failed build never holds the pin back: the pin keeps its current layers, and the PR lists the
failure under Follow-ups. The layers may keep an older `tileVersion` than the map's tiles. The planner
checks them against their own, and its provenance line says when they differ.

## Publication and planner pin

```sh
scripts/verify-routing-layers work/routing-layers/<version>
# R2_ENDPOINT, R2_ACCESS_KEY_ID and R2_SECRET_ACCESS_KEY are supplied by CI.
nix develop -c scripts/upload-routing-layers work/routing-layers/<version>
scripts/pin-routing-layers work/routing-layers/<version>/manifest.json /path/to/osrs-planner/tiles.json
```

Upload verifies all local checksums and the content address, uploads payloads with immutable cache
headers, checks remote bytes, then writes the manifest last. An existing manifest is compared and
never overwritten. Publish **before** releasing the planner pin. The pin records tile version,
plugin revision and collision/transport/separator hashes; the frontend rejects any mismatch.
Nothing runs in the tile-serving request path, and there is no unversioned latest lookup.

The validated development output is `043b756bc1e0bd384462a668`, for tile release
`2746-44385d655b71` and plugin `537092df2be681b7f47b488c1adc7be40c8d7b59`:
1,398 retained components, 1,964 effective crossings, 1,446,082 search tiles, 1,766,277 structurally
unreachable walkable tiles, and 4,536,968 more in the five sea expanses. All cuts are effective in
this revision. Its synthetic cuts are on surface plane 0; Taverley Dungeon has several natural and
retained components and unreachable fragments but no synthetic cuts, and the UI shows that rather
than inventing dungeon separators.
