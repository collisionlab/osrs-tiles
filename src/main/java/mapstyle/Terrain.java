package mapstyle;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;
import net.runelite.cache.definitions.OverlayDefinition;
import net.runelite.cache.definitions.UnderlayDefinition;
import net.runelite.cache.region.Region;
import net.runelite.cache.region.RegionLoader;

/**
 * The loaded map squares as seen from a style that needs more than the tile
 * being drawn: elevation (slope, aspect, relief) and neighbouring overlays
 * (shorelines). Heights are RuneLite's raw tile-corner heights: 0 at sea
 * level, more negative the higher the ground, 128 units to a tile.
 */
public final class Terrain
{
	public static final int TILE = 128;

	private final RegionLoader regions;
	private final IntUnaryOperator overlayColour;
	private final IntFunction<OverlayDefinition> overlays;
	private final IntFunction<UnderlayDefinition> underlays;
	private final Map<Integer, Integer> overlayCache = new HashMap<>();

	/**
	 * {@code overlayColour} maps an overlay definition id to RuneLite's
	 * colour for it; {@code overlays} maps it to the definition.
	 */
	Terrain(RegionLoader regions, IntUnaryOperator overlayColour, IntFunction<OverlayDefinition> overlays,
		IntFunction<UnderlayDefinition> underlays)
	{
		this.regions = regions;
		this.overlayColour = overlayColour;
		this.overlays = overlays;
		this.underlays = underlays;
	}

	/** The underlay (ground) definition at (x, y, z), or null without one or outside the loaded squares. */
	public UnderlayDefinition underlayDefinition(int x, int y, int z)
	{
		Region r = regions.findRegionForWorldCoordinates(x, y);
		if (r == null)
		{
			return null;
		}
		int id = r.getUnderlayId(z, x & (Region.X - 1), y & (Region.Y - 1));
		return id == 0 ? null : underlays.apply(id - 1);
	}

	/** The overlay definition at (x, y, z), or null without one or outside the loaded squares. */
	public OverlayDefinition overlayDefinition(int x, int y, int z)
	{
		Region r = regions.findRegionForWorldCoordinates(x, y);
		if (r == null)
		{
			return null;
		}
		int id = r.getOverlayId(z, x & (Region.X - 1), y & (Region.Y - 1));
		return id == 0 ? null : overlays.apply(id - 1);
	}

	/**
	 * RuneLite's colour for the overlay at (x, y, z) before any style is
	 * applied: 0 when the tile has no visible overlay, -1 outside the loaded
	 * squares.
	 */
	public int overlay(int x, int y, int z)
	{
		Region r = regions.findRegionForWorldCoordinates(x, y);
		if (r == null)
		{
			return -1;
		}
		int id = r.getOverlayId(z, x & (Region.X - 1), y & (Region.Y - 1));
		return id == 0 ? 0 : overlayCache.computeIfAbsent(id - 1, overlayColour::applyAsInt);
	}

	/**
	 * Whether (x, y, z) is under a roof: tile setting bit 0x4, which the
	 * client uses to hide roofs. It marks building interiors exactly; open
	 * yards and roofless ruins are not flagged.
	 */
	public boolean roofed(int x, int y, int z)
	{
		Region r = regions.findRegionForWorldCoordinates(x, y);
		return r != null && (r.getTileSetting(z, x & (Region.X - 1), y & (Region.Y - 1)) & 4) != 0;
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
