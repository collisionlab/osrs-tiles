package mapstyle;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;
public class TilesTest {
    @Test
    public void upperPlanesLiveUnderTheirOwnDirectory() {
        assertEquals("", Tiles.planePath(0));
        assertEquals("p1/", Tiles.planePath(1));
        assertEquals("p3/", Tiles.planePath(3));
    }

    @Test
    public void blocksAreAlignedToTheLayoutOriginAndHoldOnlyUsedSquares() {
        List<int[]> blocks = Tiles.blocks(Set.of(15 << 8 | 19, 30 << 8 | 34, 31 << 8 | 35, 47 << 8 | 19), 16);
        assertEquals(3, blocks.size());
        assertArrayEquals(new int[]{15, 19}, blocks.get(0));
        assertArrayEquals(new int[]{31, 35}, blocks.get(1));
        assertArrayEquals(new int[]{47, 19}, blocks.get(2));
    }

    @Test
    public void extentCoversEverySquareAndRejectsOnesTheLayoutCannotAddress() {
        assertArrayEquals(new int[]{15, 19, 65, 196}, Tiles.extent(Set.of(15 << 8 | 196, 65 << 8 | 19, 40 << 8 | 100)));
        assertThrows(IllegalStateException.class, () -> Tiles.extent(Set.of(14 << 8 | 50)));
    }

    @Test
    public void onlyAnAllBlackTileIsBlack() {
        BufferedImage tile = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        assertTrue(Tiles.black(tile));
        tile.setRGB(3, 3, 0x010000);
        assertFalse(Tiles.black(tile));
    }
}
