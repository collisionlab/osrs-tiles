package mapstyle;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import net.runelite.cache.definitions.*;
import static org.junit.Assert.*;
import static mapstyle.InsetTopology.*;
public class InsetTopologyTest {
    @Test
    public void surfaceCoverageExcludesRemappingAndPreservesChunkEdges() {
        List<int[]> rectangles = new ArrayList<>();
        WorldMapType0 chunk = new WorldMapType0();
        chunk.xLow = chunk.xHigh = 50;
        chunk.yLow = chunk.yHigh = 51;
        chunk.chunk_xLow = chunk.chunk_xHigh = 2;
        chunk.chunk_yLow = chunk.chunk_yHigh = 3;
        chunk.numberOfPlanes = 1;
        addSurface(chunk, rectangles);
        assertArrayEquals(new int[]{0, 3216, 3288, 8, 8}, rectangles.get(0));
        chunk.chunk_xHigh++;
        addSurface(chunk, rectangles);
        assertEquals(1, rectangles.size());

        WorldMapType1 range = new WorldMapType1();
        range.xLowerLeft = range.xUpperLeft = 50;
        range.xLowerRight = range.xUpperRight = 52;
        range.yLowerLeft = range.yLowerRight = 51;
        range.yUpperLeft = range.yUpperRight = 54;
        range.numberOfPlanes = 1;
        addSurface(range, rectangles);
        assertArrayEquals(new int[]{0, 3200, 3264, 192, 256}, rectangles.get(1));
        range.xUpperRight++;
        addSurface(range, rectangles);
        assertEquals(2, rectangles.size());

        WorldMapType2 square = new WorldMapType2();
        square.xLow = square.xHigh = 50;
        square.yLow = square.yHigh = 51;
        square.plane = 1;
        square.numberOfPlanes = 2;
        addSurface(square, rectangles);
        assertArrayEquals(new int[]{1, 3200, 3264, 64, 64}, rectangles.get(2));
        assertArrayEquals(new int[]{2, 3200, 3264, 64, 64}, rectangles.get(3));
        square.xHigh++;
        addSurface(square, rectangles);
        assertEquals(4, rectangles.size());

        WorldMapType3 chunks = new WorldMapType3();
        chunks.oldX = chunks.newX = 50;
        chunks.oldY = chunks.newY = 51;
        chunks.chunk_oldXLow = chunks.chunk_newXLow = 1;
        chunks.chunk_oldXHigh = chunks.chunk_newXHigh = 3;
        chunks.chunk_oldYLow = chunks.chunk_newYLow = 2;
        chunks.chunk_oldYHigh = chunks.chunk_newYHigh = 5;
        chunks.numberOfPlanes = 1;
        addSurface(chunks, rectangles);
        assertArrayEquals(new int[]{0, 3208, 3280, 24, 32}, rectangles.get(4));
        chunks.newY++;
        addSurface(chunks, rectangles);
        assertEquals(5, rectangles.size());
    }

    @Test
    public void fullTileVoidOverlayHidesNonemptyUnderlay() {
        MapDefinition.Tile tile = new MapDefinition.Tile();
        UnderlayDefinition underlay = new UnderlayDefinition();
        underlay.setColor(6050876);
        OverlayDefinition overlay = new OverlayDefinition();
        overlay.setRgbColor(0xff00ff);
        org.junit.Assert.assertFalse(visibleTerrain(tile, underlay, overlay));
        tile.overlayPath = 1;
        org.junit.Assert.assertTrue(visibleTerrain(tile, underlay, overlay));
        tile.overlayPath = 0;
        overlay.setTexture(1);
        org.junit.Assert.assertTrue(visibleTerrain(tile, underlay, overlay));
        overlay.setSecondaryRgbColor(0);
        org.junit.Assert.assertFalse(visibleTerrain(tile, underlay, overlay));
        org.junit.Assert.assertTrue(visibleTerrain(tile, underlay, null));
        org.junit.Assert.assertFalse(visibleTerrain(tile, null, null));
    }

}
