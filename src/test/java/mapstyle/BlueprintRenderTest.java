package mapstyle;

import com.google.gson.Gson;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.runelite.cache.ObjectManager;
import net.runelite.cache.definitions.ObjectDefinition;
import net.runelite.cache.definitions.OverlayDefinition;
import net.runelite.cache.definitions.UnderlayDefinition;
import net.runelite.cache.fs.Store;
import net.runelite.cache.region.Location;
import net.runelite.cache.util.XteaKeyManager;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Blueprint rendered from the golden example's cache (openrs2 2727, which
 * scripts/check-golden fetches into build/golden). Skipped without it.
 */
public class BlueprintRenderTest {
    private static final File CACHE = new File(System.getProperty("golden.cache", "build/golden/cache-2727"));
    /** Taverley Dungeon's map square (45, 153): cave edges are shaped overlays next to invisible ones. */
    private static final int X0 = 45 * 64, Y0 = 153 * 64;

    private static Store store;
    private static AreaRenderer renderer;

    @BeforeClass
    public static void open() throws Exception {
        Assume.assumeTrue("no golden cache at " + CACHE, new File(CACHE, "cache/main_file_cache.idx255").isFile());
        XteaKeyManager keys = new XteaKeyManager();
        try (InputStream in = new FileInputStream(new File(CACHE, "keys.json"))) {
            keys.loadKeys(in);
        }
        store = new Store(new File(CACHE, "cache"));
        store.load();
        ObjectManager objects = new ObjectManager(store);
        objects.load();
        renderer = new AreaRenderer(store, objects, keys);
    }

    @AfterClass
    public static void close() throws Exception {
        if (store != null) {
            store.close();
        }
    }

    /** Only the ground pass of a style: no objects, walls or icons. */
    private static MapStyle groundOnly(MapStyle base) {
        return new MapStyle() {
            @Override
            public int underlay(int rgb, UnderlayDefinition underlay, int x, int y, int z, Terrain terrain) {
                return base.underlay(rgb, underlay, x, y, z, terrain);
            }

            @Override
            public int overlay(int rgb, OverlayDefinition overlay, int x, int y, int z, Terrain terrain) {
                return base.overlay(rgb, overlay, x, y, z, terrain);
            }

            @Override
            public boolean keep(Location location, ObjectDefinition object) {
                return false;
            }

            @Override
            public boolean icon(BufferedImage image, net.runelite.cache.definitions.AreaDefinition area, int cx, int cy) {
                return true;
            }
        };
    }

    @Test
    public void adjoiningBlocksRenderTheSamePixels() throws Exception {
        BufferedImage whole = renderer.render(Styles.BLUEPRINT, Styles.BLUEPRINT.style, false,
            X0, Y0, X0 + 127, Y0 + 63, 0).image;
        BufferedImage west = renderer.render(Styles.BLUEPRINT, Styles.BLUEPRINT.style, false,
            X0, Y0, X0 + 63, Y0 + 63, 0).image;
        BufferedImage east = renderer.render(Styles.BLUEPRINT, Styles.BLUEPRINT.style, false,
            X0 + 64, Y0, X0 + 127, Y0 + 63, 0).image;
        for (int y = 0; y < 256; y++) {
            for (int x = 0; x < 256; x++) {
                assertEquals(whole.getRGB(x, y), west.getRGB(x, y));
                assertEquals(whole.getRGB(256 + x, y), east.getRGB(x, y));
            }
        }
    }

    /**
     * Against RuneLite's own ground pass: blueprint draws exactly where it
     * draws, keeps every invisible overlay out of the floor classes, and
     * splits a tile wherever it splits it unless both halves share a class.
     */
    @Test
    public void groundKeepsRuneLitesShapesAndVoids() throws Exception {
        BufferedImage standard = renderer.render(Styles.STANDARD, groundOnly(MapStyle.RUNELITE), false,
            X0, Y0, X0 + 63, Y0 + 63, 0).image;
        BufferedImage blueprint = renderer.render(Styles.BLUEPRINT, groundOnly(Styles.BLUEPRINT.style), false,
            X0, Y0, X0 + 63, Y0 + 63, 0).image;
        int voids = 0;
        int splitTiles = 0;
        for (int ty = 0; ty < 64; ty++) {
            for (int tx = 0; tx < 64; tx++) {
                Set<Integer> classes = new HashSet<>();
                for (int p = 0; p < 16; p++) {
                    int x = tx * 4 + p % 4;
                    int y = ty * 4 + p / 4;
                    int s = standard.getRGB(x, y) & 0xFFFFFF;
                    int b = blueprint.getRGB(x, y) & 0xFFFFFF;
                    classes.add(b);
                    if (s == 0) {
                        // Black in RuneLite: nothing there, or an invisible overlay.
                        assertTrue("pixel " + x + "," + y, b == 0 || b == BlueprintStyle.VOID);
                        voids += b == BlueprintStyle.VOID ? 1 : 0;
                    } else {
                        assertNotEquals("pixel " + x + "," + y, 0, b);
                        assertNotEquals("pixel " + x + "," + y, BlueprintStyle.VOID, b);
                    }
                    for (int q = 0; q < p; q++) {
                        int qx = tx * 4 + q % 4;
                        int qy = ty * 4 + q / 4;
                        if (standard.getRGB(x, y) == standard.getRGB(qx, qy)) {
                            assertEquals("tile " + tx + "," + ty, blueprint.getRGB(qx, qy), blueprint.getRGB(x, y));
                        }
                    }
                }
                splitTiles += classes.size() > 1 ? 1 : 0;
            }
        }
        assertTrue("the square has invisible overlays", voids > 1000);
        assertTrue("shaped tiles stay split", splitTiles > 100);
    }

    /** A small Tiles run: blueprint's files, planes and manifest match tactical's but for style and icons. */
    @Test
    public void tilesShareTacticalsLayoutAndManifest() throws Exception {
        Path out = Files.createTempDirectory("blueprint-tiles");
        // Lumbridge Castle's square, which has terrain on planes 0-2; explicit --no-icons naming only tactical.
        Tiles.main(new String[]{"--cache", new File(CACHE, "cache").getPath(), "--xteas", new File(CACHE, "keys.json").getPath(),
            "--out", out.toString(), "--styles", "tactical,blueprint", "--no-icons", "tactical",
            "--extent", "50,50,50,50", "--block", "1", "--commit", "test", "--cache-id", "2727"});
        Set<String> tactical = files(out.resolve("tactical"));
        Set<String> blueprint = files(out.resolve("blueprint"));
        assertEquals(tactical, blueprint);
        assertTrue(blueprint.contains("8/35/31.png"));
        assertTrue(blueprint.contains("p1/8/35/31.png"));
        assertTrue(blueprint.contains("p2/8/35/31.png"));
        assertTrue(blueprint.contains("3/1/0.png"));

        Gson gson = new Gson();
        Map<?, ?> tm = gson.fromJson(Files.readString(out.resolve("tactical/manifest.json")), Map.class);
        Map<?, ?> bm = gson.fromJson(Files.readString(out.resolve("blueprint/manifest.json")), Map.class);
        assertEquals("blueprint", bm.get("style"));
        assertEquals(Boolean.FALSE, bm.get("icons"));
        assertEquals(tm.keySet(), bm.keySet());
        for (Object key : tm.keySet()) {
            if (!key.equals("style") && !key.equals("icons")) {
                assertEquals(String.valueOf(key), tm.get(key), bm.get(key));
            }
        }
        Map<?, ?> index = gson.fromJson(Files.readString(out.resolve("blueprint/index.json")), Map.class);
        Set<String> pngs = blueprint.stream().filter(f -> f.endsWith(".png")).collect(Collectors.toCollection(TreeSet::new));
        assertEquals(pngs, index.keySet());
    }

    private static Set<String> files(Path dir) throws Exception {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).map(p -> dir.relativize(p).toString().replace(File.separatorChar, '/'))
                .collect(Collectors.toCollection(TreeSet::new));
        }
    }
}
