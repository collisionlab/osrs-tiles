These are historical regression inputs from tooling `experiment/map-insets`
commit `66b1bff`, not deployable assets or weekly snapshots.

`regions.json` retains only the components used by the reviewed naming
annotations: the original 280, plus those added on 2026-10-19 and 2026-10-20
(from cache 2740) when every reachable component became required, and on
2026-10-21 the pieces the first cuts separated. `anchors.json`
retains the required component IDs (`reachableRegionIds`, and the older
`insetRegionIds`), only the connections cited as evidence, and each required
component's default anchor (`regions`, from bundle `f25d6bfed0e23d5ae96e064c`'s
terrain and the planner's pinned transports). The catalogue hash covers this subset.
The naming test compiles the real authored sources and annotations against
these masks, checking every association, seed, evidence reference and required
name. Generated labels and reports belong under `build/`.

Saved route regressions live in osrs-planner, where they exercise classification,
layout and picking. The sidecar build generates complete catalogues and entrance
graphs from its selected cache and writes naming drift to `review.json`.
Cache updates do not require refreshing these historical fixtures; intentional
changes to reviewed associations may require updating the corresponding sample.
