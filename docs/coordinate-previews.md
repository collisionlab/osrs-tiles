# Coordinate preview source imagery

Coordinate previews reuse native base tiles and existing sparse inset supplemental
rasters. No duplicate upper-plane surface/pyramid is needed. `build-insets` already
extracts visible terrain on all planes, including main-world upper floors, and
passes only their actual map squares to `Tiles --plane P --squares ...`.

Existing paths remain unchanged:

* Plane 0 base: `<style>/<tileVersion>/8/<column>/<row>.png`.
* Standard supplemental: `insets/<insetVersion>/planes-standard/<plane>/8/<column>/<row>.png`.
* Tactical supplemental: `insets/<insetVersion>/planes/<plane>/8/<column>/<row>.png`.

These are 256px PNGs, north up, 4px/game square. TMS `(column,row)` maps to
map square `(15+column,19+row)`. Negative columns/rows are valid for supplemental
squares outside the base extent. Use outer `manifest.json`'s `supplemental` square
lists to distinguish known void from an unexpectedly missing tile. Do not fall
back to plane 0 on an upper-plane miss.

New inset releases add `preview-source.json`, identifying the tile/inset version
pair, cache SHA-256, renderer revision, styles, planes, origin and native scale.
The outer manifest hashes this file. Its existing inputs record renderer source,
cache/keys and build hashes, and validate matching pinned source tiles. The file
is additive, existing versions stay immutable, and raster-cache reuse avoids
regenerating unchanged PNGs. The outer manifest remains the completion marker and
is uploaded last. Existing planners ignore this additional descriptor.

`tests/test_build_insets.py` checks all four planes/both styles and descriptor
integrity. The preview application owns geometry/pixel goldens. After an approved
preview release, run the manual `coordinate-preview-consumer` workflow with the
released Action revision; it tests that external Action without dependency install.
