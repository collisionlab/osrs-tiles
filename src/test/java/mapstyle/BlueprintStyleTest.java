package mapstyle;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import net.runelite.cache.EntityOpsDefinition;
import net.runelite.cache.definitions.MapDefinition;
import net.runelite.cache.definitions.ObjectDefinition;
import net.runelite.cache.definitions.OverlayDefinition;
import net.runelite.cache.definitions.UnderlayDefinition;
import net.runelite.cache.fs.Store;
import net.runelite.cache.region.Location;
import net.runelite.cache.region.Position;
import net.runelite.cache.region.RegionLoader;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

/** The blueprint hooks on synthetic cache data: one map square, no game cache needed. */
public class BlueprintStyleTest {
    private static final BlueprintStyle STYLE = new BlueprintStyle();
    /** Map square (50, 50): tiles 3200..3263 x 3200..3263. */
    private static final int X0 = 3200, Y0 = 3200;
    private static Terrain terrain;

    /** A square of Taverley Dungeon's cave ground (underlay #664411) whose west half is roofed. */
    @BeforeClass
    public static void square() throws Exception {
        File dir = Files.createTempDirectory("blueprint-store").toFile();
        Store store = new Store(dir);
        store.load();
        RegionLoader regions = new RegionLoader(store, id -> null);
        MapDefinition map = new MapDefinition();
        map.setRegionX(50);
        map.setRegionY(50);
        MapDefinition.Tile[][][] tiles = new MapDefinition.Tile[4][64][64];
        for (int z = 0; z < 4; z++) {
            for (int x = 0; x < 64; x++) {
                for (int y = 0; y < 64; y++) {
                    MapDefinition.Tile t = new MapDefinition.Tile();
                    t.height = 0;
                    t.settings = (byte) (x < 32 ? 4 : 0);
                    t.underlayId = 1;
                    tiles[z][x][y] = t;
                }
            }
        }
        map.setTiles(tiles);
        regions.loadRegion(50 << 8 | 50, map, null);
        UnderlayDefinition cave = new UnderlayDefinition();
        cave.setColor(0x664411);
        terrain = new Terrain(regions, id -> 0, id -> null, id -> cave);
    }

    private static int outdoors(int rgb) {
        return STYLE.underlay(rgb, null, X0 + 40, Y0 + 10, 0, terrain);
    }

    private static int indoors(int rgb) {
        return STYLE.underlay(rgb, null, X0 + 4, Y0 + 10, 0, terrain);
    }

    private static int overlayOutdoors(int rgb, OverlayDefinition overlay) {
        return STYLE.overlay(rgb, overlay, X0 + 40, Y0 + 10, 0, terrain);
    }

    private static OverlayDefinition overlay(int rgb, int secondary, int texture) {
        OverlayDefinition o = new OverlayDefinition();
        o.setRgbColor(rgb);
        o.setSecondaryRgbColor(secondary);
        o.setTexture(texture);
        return o;
    }

    private static ObjectDefinition object(String name, int wallOrDoor, int mapScene, String... actions) {
        ObjectDefinition o = new ObjectDefinition();
        o.setName(name);
        o.setWallOrDoor(wallOrDoor);
        o.setMapSceneID(mapScene);
        EntityOpsDefinition ops = new EntityOpsDefinition();
        for (int i = 0; i < actions.length; i++) {
            ops.setOp(i, actions[i]);
        }
        o.setOps(ops);
        return o;
    }

    private static Location at(int type) {
        return new Location(1, type, 0, new Position(X0, Y0, 0));
    }

    @Test
    public void isRegisteredAsAForkedStyleCalledBlueprint() {
        assertSame(Styles.BLUEPRINT, Styles.parse("blueprint"));
        assertSame(Styles.BLUEPRINT, Styles.parse(" Blueprint "));
        assertEquals("blueprint", Styles.BLUEPRINT.id());
        assertTrue(Styles.BLUEPRINT.forked);
        assertTrue(Styles.BLUEPRINT.style instanceof BlueprintStyle);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Styles.parse("blue-print"));
        assertTrue(e.getMessage().contains("blueprint"));
    }

    @Test
    public void groundIsAFewFlatClasses() {
        assertEquals(BlueprintStyle.GRASS, outdoors(0x4C7A2A));
        // Taverley Dungeon's cave floor (underlay #664411) blends to these.
        assertEquals(BlueprintStyle.DIRT, outdoors(0x604010));
        assertEquals(BlueprintStyle.DIRT, outdoors(0x603000));
        assertEquals(BlueprintStyle.ROCK, outdoors(0x4D4D4D));
        // Dim brown cave blends (the Chasm of Fire) are rock, not dirt.
        assertEquals(BlueprintStyle.ROCK, outdoors(0x302020));
        assertEquals(BlueprintStyle.SAND, outdoors(0xD8C890));
        assertEquals(BlueprintStyle.SNOW, outdoors(0xE8ECEC));
        assertEquals(BlueprintStyle.UNCLASSIFIED, outdoors(0x6040A0));
    }

    @Test
    public void classesDoNotVaryFromTileToTile() {
        for (int x = 32; x < 64; x++) {
            for (int y = 0; y < 64; y++) {
                assertEquals(BlueprintStyle.DIRT, STYLE.underlay(0x604010, null, X0 + x, Y0 + y, 0, terrain));
                assertEquals(BlueprintStyle.PAVED, STYLE.overlay(0x9A9A9A, null, X0 + x, Y0 + y, 0, terrain));
            }
        }
        assertEquals(outdoors(0x604010), outdoors(0x604010));
    }

    @Test
    public void roofedFloorsAreStoneWoodOrOther() {
        assertEquals(BlueprintStyle.STONE_FLOOR, indoors(0x808080));
        assertEquals(BlueprintStyle.WOOD_FLOOR, indoors(0x7A5530));
        assertEquals(BlueprintStyle.OTHER_FLOOR, indoors(0x803040));
        assertEquals(BlueprintStyle.STONE_FLOOR, STYLE.overlay(0x9A9A9A, null, X0 + 4, Y0, 0, terrain));
        // The same grey is a paved surface outside.
        assertEquals(BlueprintStyle.PAVED, overlayOutdoors(0x9A9A9A, null));
    }

    @Test
    public void invisibleOverlaysAreVoidNotFloor() {
        OverlayDefinition invisible = overlay(0xFF00FF, -1, -1);
        int floorClass = overlayOutdoors(0, invisible);
        assertEquals(BlueprintStyle.VOID, floorClass);
        assertEquals(BlueprintStyle.VOID, STYLE.overlay(0, invisible, X0 + 4, Y0, 0, terrain));
        // Drawn (non-zero) and unlike every terrain class, and unlike the renderer's own black.
        assertNotEquals(0, BlueprintStyle.VOID);
        for (int c : new int[]{BlueprintStyle.DARK, BlueprintStyle.GRASS, BlueprintStyle.DIRT, BlueprintStyle.SAND,
            BlueprintStyle.ROCK, BlueprintStyle.PAVED, BlueprintStyle.TRAIL, BlueprintStyle.STONE_FLOOR,
            BlueprintStyle.WOOD_FLOOR, BlueprintStyle.OTHER_FLOOR, BlueprintStyle.WATER, BlueprintStyle.LAVA,
            BlueprintStyle.UNCLASSIFIED}) {
            assertNotEquals(BlueprintStyle.VOID, c);
        }
    }

    @Test
    public void nearBlackTerrainIsDarkWhereverItIs() {
        // TzHaar's floor is a #030303 underlay; the Chasm of Fire's void a #000001 overlay with a water hue.
        assertEquals(BlueprintStyle.DARK, outdoors(0x030303));
        assertEquals(BlueprintStyle.DARK, indoors(0x030303));
        assertEquals(BlueprintStyle.DARK, overlayOutdoors(0x000001, overlay(0xFF00FF, 0x000000, -1)));
    }

    @Test
    public void waterAndLavaAreFlat() {
        // A river: two-colour overlay with a blue secondary colour.
        assertEquals(BlueprintStyle.WATER, overlayOutdoors(0x687DA9, overlay(0xFF00FF, 0x687DAA, -1)));
        // Ocean: textured.
        assertEquals(BlueprintStyle.WATER, overlayOutdoors(0x4A5D8A, overlay(0x000000, -1, 133)));
        assertEquals(BlueprintStyle.WATER, STYLE.overlay(0x4A5D8A, overlay(0x000000, -1, 133), X0 + 4, Y0, 0, terrain));
        assertEquals(BlueprintStyle.LAVA, overlayOutdoors(0xF07C15, overlay(0x000000, -1, 31)));
    }

    @Test
    public void plainVioletFloorsAreNotWater() {
        // The Catacombs of Kourend's floor: plain #423f73.
        assertEquals(BlueprintStyle.UNCLASSIFIED, overlayOutdoors(0x3A3865, overlay(0x423F73, -1, -1)));
        // The same colour on a textured overlay is still water.
        assertEquals(BlueprintStyle.WATER, overlayOutdoors(0x3A3865, overlay(0x423F73, -1, 145)));
    }

    @Test
    public void doorLikeWallsNeedAnAction() {
        assertEquals(BlueprintStyle.WALL, STYLE.wall(0xEEEEEE, object("null", 0, -1)));
        assertEquals(BlueprintStyle.DOOR, STYLE.wall(0xEE0000, object("Door", 1, -1, "Open")));
        assertEquals(BlueprintStyle.DOOR, STYLE.wall(0xEE0000, object("Loose Railing", 1, -1, null, "Squeeze-through")));
        // Interactive by opcode 19 but with nothing to do: drawn as a wall.
        assertEquals(BlueprintStyle.WALL, STYLE.wall(0xEE0000, object("", 1, -1)));
    }

    @Test
    public void iconsAreAlwaysHidden() {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        assertTrue(STYLE.icon(image, null, 4, 4));
        for (int x = 0; x < 8; x++) {
            for (int y = 0; y < 8; y++) {
                assertEquals(0, image.getRGB(x, y) & 0xFFFFFF);
            }
        }
        assertFalse(STYLE.drawsIcons());
        assertFalse(STYLE.withoutIcons().drawsIcons());
        assertTrue(MapStyle.RUNELITE.drawsIcons());
        assertFalse(MapStyle.RUNELITE.withoutIcons().drawsIcons());
    }

    @Test
    public void sceneryIsDroppedButStructureKept() {
        // Walls, even ones drawn with a map-scene sprite, and objects without a sprite.
        for (int type : new int[]{0, 1, 2, 3, 9}) {
            assertTrue(STYLE.keep(at(type), object("null", 0, 22)));
        }
        assertTrue(STYLE.keep(at(10), object("Crate", 0, -1)));
        // Passages.
        assertTrue(STYLE.keep(at(10), object("Ladder", 1, 7, "Climb-up", "Climb-down")));
        assertTrue(STYLE.keep(at(10), object("Rockslide", 1, 24, "Climb-over")));
        assertTrue(STYLE.keep(at(22), object("Rope bridge", 1, 22, "Cross")));
        assertTrue(STYLE.keep(at(11), object("Crevice", 1, 7, null, "Squeeze through")));
        // Scenery.
        assertFalse(STYLE.keep(at(10), object("Tree", 1, 0, "Chop down")));
        assertFalse(STYLE.keep(at(10), object("Iron rocks", 1, 146, "Mine", "Prospect")));
        assertFalse(STYLE.keep(at(22), object("null", 0, 205)));
        assertFalse(STYLE.keep(at(11), object("Fountain", 0, 8)));
    }
}
