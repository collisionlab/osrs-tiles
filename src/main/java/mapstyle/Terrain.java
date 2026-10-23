package mapstyle;

import java.awt.Color;
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
	private final Map<Integer, Boolean> sandDefinitions = new HashMap<>();

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
	 * Whether at least three quarters of the ground in the 5x5 window is sand
	 * by definition: a sand
	 * hue at brightness 0.55 or more. RuneLite's blended colour cannot tell
	 * the Kharidian Desert (definitions #b19a3d, #d0c074, #946b03) from
	 * Varlamore's olive grass (#78680b): both blend to about hue 0.13,
	 * saturation 0.8, value 0.6. Their definitions differ in brightness. A
	 * three-quarter majority keeps the boundary smooth.
	 */
	public boolean sandy(int x, int y, int z)
	{
		int sand = 0;
		int ground = 0;
		for (int dx = -2; dx <= 2; dx++)
		{
			for (int dy = -2; dy <= 2; dy++)
			{
				UnderlayDefinition u = underlayDefinition(x + dx, y + dy, z);
				if (u == null)
				{
					continue;
				}
				ground++;
				if (isSand(u))
				{
					sand++;
				}
			}
		}
		// A strong majority: real desert is almost all sand, while Varlamore's
		// grassland mixes in up to about half sandy definitions.
		return sand * 4 >= ground * 3;
	}

	private boolean isSand(UnderlayDefinition underlay)
	{
		return sandDefinitions.computeIfAbsent(underlay.getId(), id ->
		{
			int rgb = underlay.getColor();
			float[] hsb = Color.RGBtoHSB(rgb >> 16 & 0xFF, rgb >> 8 & 0xFF, rgb & 0xFF, null);
			return hsb[0] >= 0.08f && hsb[0] < 0.16f && hsb[2] >= 0.55f;
		});
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
