package mapstyle;

import net.runelite.cache.region.Region;
import net.runelite.cache.region.RegionLoader;

/**
 * Elevation of the loaded map squares, for styles that need more than the
 * tile being drawn (slope, aspect, relief). Heights are RuneLite's raw
 * tile-corner heights: 0 at sea level, more negative the higher the
 * ground, 128 units to a tile.
 */
public final class Terrain
{
	public static final int TILE = 128;

	private final RegionLoader regions;

	Terrain(RegionLoader regions)
	{
		this.regions = regions;
	}

	/** Height at world tile (x, y, z), or {@code fallback} outside the loaded squares. */
	public int height(int x, int y, int z, int fallback)
	{
		Region r = regions.findRegionForWorldCoordinates(x, y);
		return r == null ? fallback : r.getTileHeight(z, x & (Region.X - 1), y & (Region.Y - 1));
	}

	/** Height at world tile (x, y, z); the tile must be in a loaded square. */
	public int height(int x, int y, int z)
	{
		return height(x, y, z, 0);
	}
}
