# map-style: hand-off

What this experiment produced, how we got there, how to pick it up, and
what blocks turning it into the planner's real map. The README covers how
the renderer and styles work. This file covers status and next steps.

## Status (2026-10-16)

- Branch `experiment/map-style` in the tooling repo (worktree
  `worktrees/map-style`), 15 commits on `origin/master`, head `c62ee5d`.
- **The branch is not on any remote.** We agreed to keep it local while
  experimenting, so this laptop has the only copy. Push it somewhere
  (a fork branch, no PR) before anything else.
- Everything is in the standalone Gradle build `map-style/`. Nothing in the
  plugin, the pathfinder, the web planner or production tiles was changed.
- Verified 2026-10-16: re-running the Lumbridge command in the README
  reproduces every PNG in `examples/lumbridge/` byte for byte, including
  `standard.png == runelite.png`. That is the fork parity check.
- Published previews (private claude.ai artifacts):
  - Whole-world tactical viewer: https://claude.ai/artifact/Xs9eBUmJCTgjD81UVrR2iB
  - Style comparison gallery: https://claude.ai/artifact/1XqKxYsjLgzm39bW7dKXWk.
    Its sources lived in a session scratchpad and are gone. Rebuild it from
    `examples/` if needed.
- The favourite is `tactical` (olive daylight). `tactical-dusk`, `-field`
  and `-classic` are palette variants, kept for comparison.

## How we got here

It was one session on 2026-10-15, with the user reviewing each render and
committing each step:

1. **Bootstrap.** We studied Explv/osrs_map_tiles (the tiles the web
   planner uses today) and reused its architecture: RuneLite's
   `MapImageDumper` reading an openrs2 cache. We did not use its code, which
   is unlicensed. We added subset rendering (map squares plus a one-square
   margin), a manifest, and pinned the RuneLite version.
2. **Extension point.** RuneLite keeps every colour decision private, so we
   forked `MapImageDumper` into `StyledMapImageDumper` (BSD-2, notice kept)
   with `MapStyle` hooks. **Every style re-renders from the cache.** There is
   no post-processing of the stock image. Hooks recolour per tile, using
   decoded cache data plus `Terrain` lookups of neighbouring tiles.
3. **Styles.** `declutter`, `muted` (proves the hooks work) and `relief`
   (hillshade) came first. Then `tactical`, asked for as "matches the web
   viewer: slightly pixelated, olives, Advance Wars". The user's reaction:
   "absolutely sick".
4. **Tactical iterations**, each driven by user feedback:
   - icons reduced to route-planning badges
   - water banded by distance to shore
   - three palette variants
   - a whole-world render with a Leaflet viewer
   - Sailing seas treated as one water class
   - Wilderness, Morytania and snow terrain
   - a land-side coast rim
   - the fetid sea (near Jubbly Jive) recognised as water
   - per-sea tints, with the frozen sea at 30% because full strength looked
     white
   - textured building floors from the roof flag ("buildings look pasted
     on")
   - a desert-vs-grass fix found in a final review pass over Falador,
     Ardougne, Hosidius, Karamja, Pollnivneach and Catherby

Approaches that were tried and rejected are recorded in the README under
the tactical style section.

## Picking it up

```bash
cd worktrees/map-style
T=../../shortest-path-tooling          # cache/ + keys.json (maintenance.py cache)
# quick loop: one area, stock vs style (~6 s)
./gradlew -p map-style run --args="--cache $T/cache --xteas $T/keys.json \
    --regions 12850,12851,13106,13107 --plane 0 --styles runelite,tactical \
    --out build/map-style/scratch"
```

For the whole world, follow "The whole world" in the README (10 GB heap,
about 20 s, then `Pyramid` and `viewer/index.html`).

After any change to the fork or a RuneLite bump, re-render
`examples/lumbridge` and check that `standard.png` still has the same hash
as `runelite.png`.

## Blocking production

Ordered roughly by how hard they block. Points 1–3 have to be settled
before any integration work is worth doing.

1. **Rights to publish imagery.** Rendered tiles are Jagex content. The
   planner already hot-links Explv's tiles with "Explv / Jagex" attribution,
   so there is precedent, but serving our own renders means hosting Jagex
   imagery ourselves. That needs an explicit decision. Explv's repo has no
   licence: don't copy its code or tiles. Asking Explv is the alternative.
2. **Tile scheme mismatch with the web planner.**
   `shortest-path-web/src/map/OsrsMap.tsx` expects Explv's layout:
   - 256-px TMS tiles, zoom 4–11
   - 32 px per game tile at zoom 11
   - `OFFSET_X 960`, `OFFSET_Y 6208`, `MAP_HEIGHT 364544`

   `Pyramid` instead writes:
   - 1024-px non-TMS tiles, zoom 0–4
   - 4 px per game tile
   - origin at the render's top-left (`WORLD_X0 960`, `WORLD_Y1 4223`)

   To fix this, write a pyramid in the planner's scheme. 4 px per tile is
   zoom 8, so serve native zooms 4–8 and let Leaflet upscale 9–11 with
   pixelated scaling (`maxNativeZoom 8`). Or render at a larger
   `MAP_SCALE`. Either way, check the result against the planner's
   `toLatLng`/`fromLatLng`. That is a decision for whoever owns the web
   planner. This experiment is not allowed to change it.
3. **Coverage.** Only the surface of plane 0 has been rendered: the
   rectangle 960–4031 × 2048–4223. Explv's plane-0 tiles also include the
   underground areas (dungeons and caves at y ≈ 9000+, e.g. the Lumbridge
   cellar), where routes regularly go. The tactical style
   has never been looked at there. Black void, cave floors and dungeon
   water are unknown territory. Planes 1–3 are also unrendered, though the
   planner only shows plane 0 today.
4. **Hosting and size.** The surface pyramid is about 22 MB. A full
   planner-scheme pyramid with underground areas will be several times
   that. Somewhere has to host it; hot-linking another repo's raw files is
   not a plan for our own tiles.
5. **Regeneration pipeline.** Everything is run by hand today:
   - the cache comes from the tooling's `maintenance.py cache`
   - the whole-world render needs a 10 GB heap
   - there is no CI job, no incremental update (Explv re-encodes only tiles
     whose SSIM changed), and no game-update trigger
   - `net.runelite:cache` is pinned to 1.13.2 because the fork must match
     it. Every bump needs a re-diff of the fork against upstream
     `MapImageDumper` plus the parity check.
6. **Repository home.** The code is a standalone Gradle project inside the
   tooling repo on an experiment branch. Production needs a decision on
   where it lives (tooling, or alongside the web planner's assets) and a
   normal review. Keep the fork isolated so the BSD-2 notice and the parity
   check stay simple.
7. **No automated tests.** Correctness rests on the parity hash and on
   looking at renders. At minimum, add a test that renders
   `examples/lumbridge` and compares hashes, so style changes are
   deliberate and fork drift is caught.
8. **Icons are baked into the raster.** Bank, altar, anvil, transport,
   shortcut, dock and ladder badges are drawn into the PNG. The planner
   already draws its own bank markers, so they would appear twice, and
   baked icons can't be toggled or clicked. Decide whether production
   tiles carry icons or whether they move to a viewer-side layer (one of
   the open ideas).

### Not blocking, but visible

- Doors are 1-px orange ticks. Making them bigger needs a change to the
  fork's wall drawing.
- Castles (e.g. Ardougne) lose RuneLite's heavy walls. Thicker outer walls
  between roofed and unroofed tiles would fix this.
- Trees and rocks still use RuneScape sprites. Our own forest and mountain
  glyphs need an object-sprite hook.
- Ladders and stairs inside buildings use stock sprites. They matter for
  routing and need the same hook.
- The apothecary sprite is not mapped to a badge.
- `tactical-field` contours wobble in flat grassland. Smoothing heights
  first would fix it.
- Roofless rooms render as terrain, and open-air roofed structures (Varrock
  gazebo) get a muted ground tint. Both follow the data.
- The palette has only been checked by eye against the planner's CSS.
- If the planner gains a dark theme, `tactical-dusk` would be a second
  tile set, doubling points 4–5.
