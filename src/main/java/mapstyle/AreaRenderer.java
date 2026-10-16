package mapstyle;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.TreeSet;
import net.runelite.cache.MapImageDumper;
import net.runelite.cache.ObjectManager;
import net.runelite.cache.fs.Store;
import net.runelite.cache.region.Region;
import net.runelite.cache.region.RegionLoader;
import net.runelite.cache.util.XteaKeyManager;

/**
 * Renders a rectangle of world tiles from a cache in one style. Shared by
 * {@link RenderRegions} (one area, many styles) and {@link Tiles} (the whole
 * map in blocks).
 */
final class AreaRenderer
{
	static final int MAP_SCALE = 4; // MapImageDumper's pixels per tile

	private final Store store;
	private final ObjectManager objects;
	private final XteaKeyManager keys;
	private final RegionLoader probe;

	AreaRenderer(Store store, ObjectManager objects, XteaKeyManager keys)
	{
		this.store = store;
		this.objects = objects;
		this.keys = keys;
		this.probe = new RegionLoader(store, keys);
	}

	/** One rendered area plus what the cache had for it. */
	static final class Result
	{
		/** The area, north up, {@link #MAP_SCALE} pixels per tile. */
		final BufferedImage image;
		/** Map squares inside the area with terrain in the cache. */
		final TreeSet<Integer> present = new TreeSet<>();
		/** Present map squares whose objects are locked by a missing XTEA key. */
		final TreeSet<Integer> missingKeys = new TreeSet<>();

		Result(BufferedImage image)
		{
			this.image = image;
		}
	}

	/** Whether the cache has terrain for a map square. */
	boolean hasMap(int regionId) throws IOException
	{
		return probe.loadMapDef(regionId) != null;
	}

	/**
	 * Renders world tiles x0..x1, y0..y1 (inclusive) of a plane. Loads a
	 * one-square margin first: RuneLite blends underlays across a 5-tile
	 * window and draws objects that overhang from neighbours, so the margin
	 * keeps the area's edges identical to a full-world render. Parts of the
	 * area with no map squares at all stay black.
	 *
	 * @return null when no map square in the area has terrain
	 */
	Result render(Styles style, MapStyle hooks, boolean labels, int x0, int y0, int x1, int y1, int plane) throws IOException
	{
		RegionLoader regions = new RegionLoader(store, keys);
		for (int rx = (x0 >> 6) - 1; rx <= (x1 >> 6) + 1; rx++)
		{
			for (int ry = (y0 >> 6) - 1; ry <= (y1 >> 6) + 1; ry++)
			{
				if (rx >= 0 && ry >= 0 && rx < 256 && ry < 256)
				{
					regions.loadRegionFromArchive(rx << 8 | ry);
				}
			}
		}

		int w = (x1 - x0 + 1) * MAP_SCALE;
		int h = (y1 - y0 + 1) * MAP_SCALE;
		Result result = new Result(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB));
		for (Region region : regions.getRegions())
		{
			int bx = region.getBaseX();
			int by = region.getBaseY();
			if (bx >= x0 && bx <= x1 && by >= y0 && by <= y1)
			{
				result.present.add(region.getRegionID());
				if (region.getLocations().isEmpty() && probe.loadLocDef(region.getRegionID()) == null)
				{
					result.missingKeys.add(region.getRegionID());
				}
			}
		}
		if (result.present.isEmpty())
		{
			return null;
		}

		// MapStyle.keep edits the decoded location lists, so each render
		// loads its own regions.
		for (Region region : regions.getRegions())
		{
			region.getLocations().removeIf(loc -> !hooks.keep(loc, objects.getObject(loc.getId())));
		}

		BufferedImage full;
		if (style.forked)
		{
			StyledMapImageDumper dumper = new StyledMapImageDumper(store, regions);
			dumper.setStyle(hooks);
			dumper.setLowMemory(false);
			dumper.setRenderLabels(labels);
			full = dumper.load().drawMap(plane);
		}
		else
		{
			MapImageDumper dumper = new MapImageDumper(store, regions);
			dumper.setLowMemory(false);
			dumper.setRenderLabels(labels);
			full = dumper.load().drawMap(plane);
		}

		// drawMap() spans the loaded regions' bounds; pixel row 0 is the
		// northern edge of the highest region. Copy the overlap with the area.
		int minX = regions.getLowestX().getBaseX();
		int maxY = regions.getHighestY().getBaseY() + Region.Y;
		int sx = (x0 - minX) * MAP_SCALE;
		int sy = (maxY - 1 - y1) * MAP_SCALE;
		int left = Math.max(0, -sx);
		int top = Math.max(0, -sy);
		int right = Math.min(w, full.getWidth() - sx);
		int bottom = Math.min(h, full.getHeight() - sy);
		if (right > left && bottom > top)
		{
			Graphics2D g = result.image.createGraphics();
			g.drawImage(full.getSubimage(sx + left, sy + top, right - left, bottom - top), left, top, null);
			g.dispose();
		}
		return result;
	}
}
