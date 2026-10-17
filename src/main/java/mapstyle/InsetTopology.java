package mapstyle;

import com.google.gson.Gson;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.cache.IndexType;
import net.runelite.cache.OverlayManager;
import net.runelite.cache.UnderlayManager;
import net.runelite.cache.definitions.MapDefinition;
import net.runelite.cache.definitions.OverlayDefinition;
import net.runelite.cache.definitions.UnderlayDefinition;
import net.runelite.cache.definitions.WorldMapDefinition;
import net.runelite.cache.definitions.WorldMapTypeBase;
import net.runelite.cache.definitions.WorldMapType0;
import net.runelite.cache.definitions.WorldMapType1;
import net.runelite.cache.definitions.WorldMapType2;
import net.runelite.cache.definitions.WorldMapType3;
import net.runelite.cache.definitions.loaders.WorldMapLoader;
import net.runelite.cache.fs.Archive;
import net.runelite.cache.fs.FSFile;
import net.runelite.cache.fs.Index;
import net.runelite.cache.fs.Store;
import net.runelite.cache.region.RegionLoader;

/** Terrain-only extraction: missing landscape keys cannot silently remove a dungeon. */
public final class InsetTopology {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: InsetTopology CACHE_DIR OUTPUT_JSON");
        Path output = Paths.get(args[1]);
        Map<String, Object> result = new LinkedHashMap<>();
        List<int[]> spans = new ArrayList<>();
        List<Integer> loaded = new ArrayList<>();
        List<int[]> surface = new ArrayList<>();
        try (Store store = new Store(new java.io.File(args[0]))) {
            store.load();
            UnderlayManager underlays = new UnderlayManager(store);
            underlays.load();
            OverlayManager overlays = new OverlayManager(store);
            overlays.load();
            RegionLoader loader = new RegionLoader(store, id -> null);
            // Match RegionLoader's archive scan range; larger named lookups can collide with non-map archive hashes.
            for (int id = 0; id < 25287; id++) {
                MapDefinition map;
                try {
                    map = loader.loadMapDef(id);
                } catch (Exception e) {
                    throw new IllegalStateException("Cannot decode terrain map square " + id, e);
                }
                if (map == null) continue;
                loaded.add(id);
                for (int plane = 0; plane < 4; plane++) {
                    for (int y = 0; y < 64; y++) {
                        int start = -1;
                        for (int x = 0; x <= 64; x++) {
                            boolean content = false;
                            if (x < 64) {
                                MapDefinition.Tile tile = map.getTiles()[plane][x][y];
                                OverlayDefinition overlay = tile.overlayId == 0 ? null
                                    : overlays.provide(Short.toUnsignedInt(tile.overlayId) - 1);
                                if (tile.overlayId != 0 && overlay == null) {
                                    throw new IllegalStateException("Unknown overlay in map square " + id);
                                }
                                UnderlayDefinition underlay = tile.underlayId == 0 ? null
                                    : underlays.provide(Short.toUnsignedInt(tile.underlayId) - 1);
                                if (tile.underlayId != 0 && underlay == null) {
                                    throw new IllegalStateException("Unknown underlay in map square " + id);
                                }
                                content = visibleTerrain(tile, underlay, overlay);
                            }
                            if (content && start < 0) start = x;
                            if (!content && start >= 0) {
                                spans.add(new int[]{plane, (id & 255) * 64 + y,
                                    (id >> 8) * 64 + start, (id >> 8) * 64 + x});
                                start = -1;
                            }
                        }
                    }
                }
            }
            Index index = store.getIndex(IndexType.WORLDMAP);
            Archive details = index.isNamed() ? index.findArchiveByName("details") : index.getArchive(0);
            boolean found = false;
            for (FSFile file : details.getFiles(store.getStorage().loadArchive(details)).getFiles()) {
                WorldMapDefinition map = new WorldMapLoader().load(file.getContents(), file.getFileId());
                if (!"main".equals(map.safeName)) continue;
                found = true;
                for (WorldMapTypeBase section : map.regionList) addSurface(section, surface);
            }
            if (!found || surface.isEmpty()) throw new IllegalStateException("Main world-map coverage missing");
            result.put("mapIndexRevision", store.getIndex(IndexType.MAPS).getRevision());
        }
        if (spans.isEmpty()) throw new IllegalStateException("No terrain extracted");
        result.put("schemaVersion", 1);
        result.put("loadedMapSquares", loaded);
        result.put("surfaceRects", surface);
        result.put("spans", spans);
        Files.createDirectories(output.toAbsolutePath().getParent());
        try (Writer writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            new Gson().toJson(result, writer);
        }
        System.out.println("Inset topology: " + loaded.size() + " map squares, " + spans.size() + " terrain spans -> " + output);
    }

    // Full-tile overlays replace the underlay, even the magenta void sentinel.
    static boolean visibleTerrain(MapDefinition.Tile tile, UnderlayDefinition underlay, OverlayDefinition overlay) {
        boolean base = underlay != null && underlay.getColor() != 0;
        if (overlay == null) return base;
        int secondary = overlay.getSecondaryRgbColor();
        boolean top = secondary != -1 ? secondary != 0 && secondary != 0xff00ff
            : overlay.getTexture() >= 0 || (overlay.getRgbColor() != 0 && overlay.getRgbColor() != 0xff00ff);
        return tile.overlayPath == 0 ? top : base || top;
    }

    // Only identity sections belong on the geographic surface. Remapped sections are inset candidates.
    static void addSurface(WorldMapTypeBase section, List<int[]> rectangles) {
        if (section instanceof WorldMapType2) {
            WorldMapType2 s = (WorldMapType2) section;
            if (s.xLow == s.xHigh && s.yLow == s.yHigh)
                add(rectangles, s.plane, s.numberOfPlanes, s.xLow * 64, s.yLow * 64, 64, 64);
        } else if (section instanceof WorldMapType0) {
            WorldMapType0 s = (WorldMapType0) section;
            if (s.xLow == s.xHigh && s.yLow == s.yHigh && s.chunk_xLow == s.chunk_xHigh && s.chunk_yLow == s.chunk_yHigh)
                add(rectangles, s.plane, s.numberOfPlanes, s.xLow * 64 + s.chunk_xLow * 8, s.yLow * 64 + s.chunk_yLow * 8, 8, 8);
        } else if (section instanceof WorldMapType1) {
            WorldMapType1 s = (WorldMapType1) section;
            if (s.xLowerLeft == s.xUpperLeft && s.yLowerLeft == s.yLowerRight
                && s.xLowerRight == s.xUpperRight && s.yUpperLeft == s.yUpperRight)
                add(rectangles, s.plane, s.numberOfPlanes, s.xLowerLeft * 64, s.yLowerLeft * 64,
                    (s.xLowerRight - s.xLowerLeft + 1) * 64, (s.yUpperLeft - s.yLowerLeft + 1) * 64);
        } else if (section instanceof WorldMapType3) {
            WorldMapType3 s = (WorldMapType3) section;
            if (s.oldX == s.newX && s.oldY == s.newY && s.chunk_oldXLow == s.chunk_newXLow
                && s.chunk_oldYLow == s.chunk_newYLow && s.chunk_oldXHigh == s.chunk_newXHigh
                && s.chunk_oldYHigh == s.chunk_newYHigh)
                add(rectangles, s.oldPlane, s.numberOfPlanes, s.oldX * 64 + s.chunk_oldXLow * 8,
                    s.oldY * 64 + s.chunk_oldYLow * 8, (s.chunk_oldXHigh - s.chunk_oldXLow + 1) * 8,
                    (s.chunk_oldYHigh - s.chunk_oldYLow + 1) * 8);
        } else {
            throw new IllegalArgumentException("Unknown world-map section " + section.getClass());
        }
    }

    private static void add(List<int[]> rectangles, int plane, int count, int x, int y, int w, int h) {
        for (int p = plane; p < plane + count; p++) rectangles.add(new int[]{p, x, y, w, h});
    }
}
