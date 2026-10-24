package mapstyle.routing;

import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import javax.imageio.ImageIO;

/**
 * The production components as a tile pyramid in the styles' layout ({@code explv-tms-v1}, see
 * docs/tiles.md): 256 px tiles, rows counted from the south, tile (c, r) at zoom 8 the map square
 * (15 + c, 19 + r) at 4 px per game tile, zooms 3 to 7 each half the one above. Every search tile is
 * its component's palette colour, everything else transparent; tiles with no search tile aren't
 * written. Colours never blend: a pixel covering several game tiles takes the commonest colour among them.
 */
public final class ComponentTiles
{
	/**
	 * Bold and easy to tell apart, none magenta (the planner's unreachable mask). Neighbouring
	 * components never share one (see {@link #colour}).
	 */
	public static final List<String> PALETTE = List.of("f2dc1e", "4c55e8", "3fd6b4", "f05a3c", "4cc94a",
		"f59a2a", "a45cf0", "38b6f5", "b8e43a", "f07ab0");
	static final int SIZE = 256;
	static final int NATIVE_ZOOM = 8;
	static final int MIN_ZOOM = 3;
	/** The layout's first map square. */
	static final int ORIGIN_X = 15, ORIGIN_Y = 19;

	private static final IndexColorModel MODEL = model();

	private ComponentTiles()
	{
	}

	private static IndexColorModel model()
	{
		int n = PALETTE.size() + 1;
		byte[] r = new byte[n], g = new byte[n], b = new byte[n], a = new byte[n];
		for (int i = 1; i < n; i++)
		{
			int rgb = Integer.parseInt(PALETTE.get(i - 1), 16);
			r[i] = (byte) (rgb >> 16);
			g[i] = (byte) (rgb >> 8);
			b[i] = (byte) rgb;
			a[i] = (byte) 255;
		}
		return new IndexColorModel(4, n, r, g, b, a);
	}

	/**
	 * A palette index for every component: the largest first, each the first colour free of its
	 * neighbours' (joined by a crossing, or sharing a tile edge) from a place its id picks, so
	 * components with no neighbours vary too.
	 */
	public static void colour(RoutingLayers.Export export)
	{
		int count = PALETTE.size();
		List<RoutingLayers.Component> order = export.components.values().stream()
			.sorted((a, b) -> a.tileCount != b.tileCount ? Integer.compare(b.tileCount, a.tileCount) : Integer.compare(a.id, b.id))
			.collect(java.util.stream.Collectors.toList());
		Map<Integer, Integer> colours = new HashMap<>();
		for (RoutingLayers.Component component : order)
		{
			Set<Integer> next = new HashSet<>(component.neighbours);
			next.addAll(export.touching.getOrDefault(component.id, Set.of()));
			for (var other : export.components.values())
			{
				if (other.neighbours.contains(component.id))
				{
					next.add(other.id);
				}
			}
			Set<Integer> taken = new HashSet<>();
			for (int id : next)
			{
				Integer colour = colours.get(id);
				if (colour != null)
				{
					taken.add(colour);
				}
			}
			int start = Integer.remainderUnsigned(component.id * 0x9E3779B1, count);
			int colour = start;
			for (int i = 0; i < count; i++)
			{
				if (!taken.contains((start + i) % count))
				{
					colour = (start + i) % count;
					break;
				}
			}
			colours.put(component.id, colour);
			component.colour = colour;
		}
	}

	/** Every tile of the pyramid that shows a search tile, by path (`components/[p1/]z/x/y.png`). */
	public static SortedMap<String, byte[]> tiles(RoutingLayers.Export export)
	{
		// Palette index + 1 per tile, by plane and map square; zero where no component owns the tile.
		Map<Long, byte[]> squares = new HashMap<>();
		for (var entry : export.squares.entrySet())
		{
			String[] parts = entry.getKey().replace(".bin", "").split("/");
			int plane = Integer.parseInt(parts[0].substring(1)), sx = Integer.parseInt(parts[1]), sy = Integer.parseInt(parts[2]);
			if (sx < ORIGIN_X || sy < ORIGIN_Y)
			{
				continue;
			}
			int[] owners = entry.getValue().owners;
			byte[] colours = null;
			for (int i = 0; i < 4096; i++)
			{
				if (owners[i] >= 0)
				{
					colours = colours == null ? new byte[4096] : colours;
					colours[i] = (byte) (export.components.get(owners[i]).colour + 1);
				}
			}
			if (colours != null)
			{
				squares.put(key(plane, sx, sy), colours);
			}
		}
		SortedMap<String, byte[]> files = new TreeMap<>();
		for (int z = NATIVE_ZOOM; z >= MIN_ZOOM; z--)
		{
			int span = 1 << (NATIVE_ZOOM - z);
			Set<Long> wanted = new HashSet<>();
			for (long square : squares.keySet())
			{
				wanted.add(key(plane(square), Math.floorDiv(sx(square) - ORIGIN_X, span), Math.floorDiv(sy(square) - ORIGIN_Y, span)));
			}
			for (long tile : wanted)
			{
				byte[] png = render(squares, plane(tile), sx(tile), sy(tile), z);
				if (png != null)
				{
					int plane = plane(tile);
					files.put("components/" + (plane == 0 ? "" : "p" + plane + "/") + z + "/" + sx(tile) + "/" + sy(tile) + ".png", png);
				}
			}
		}
		return files;
	}

	/** Tile (c, r) at zoom z, or null when no pixel samples a search tile. */
	private static byte[] render(Map<Long, byte[]> squares, int plane, int c, int r, int z)
	{
		// World tiles per pixel, and the tile's south-west world corner.
		double perPixel = (double) (64 << (NATIVE_ZOOM - z)) / SIZE;
		int x0 = (ORIGIN_X + c * (1 << (NATIVE_ZOOM - z))) * 64, y0 = (ORIGIN_Y + r * (1 << (NATIVE_ZOOM - z))) * 64;
		BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_BYTE_BINARY, MODEL);
		var raster = image.getRaster();
		boolean any = false;
		// Zoomed in, the game tile under each pixel; zoomed out, the commonest colour of the block of game
		// tiles a pixel covers, so small components stay visible.
		int block = Math.max(1, (int) perPixel);
		int[] counts = new int[PALETTE.size() + 1];
		for (int py = 0; py < SIZE; py++)
		{
			for (int px = 0; px < SIZE; px++)
			{
				int colour;
				if (block == 1)
				{
					int wx = x0 + (int) Math.floor((px + 0.5) * perPixel), wy = y0 + (int) Math.floor((SIZE - py - 0.5) * perPixel);
					colour = at(squares, plane, wx, wy);
				}
				else
				{
					java.util.Arrays.fill(counts, 0);
					int bx = x0 + px * block, by = y0 + (SIZE - py - 1) * block;
					for (int dy = 0; dy < block; dy++)
					{
						for (int dx = 0; dx < block; dx++)
						{
							counts[at(squares, plane, bx + dx, by + dy)]++;
						}
					}
					colour = 0;
					for (int i = 1; i < counts.length; i++)
					{
						if (counts[i] > counts[colour] || (colour == 0 && counts[i] > 0))
						{
							colour = i;
						}
					}
				}
				if (colour != 0)
				{
					raster.setSample(px, py, 0, colour);
					any = true;
				}
			}
		}
		if (!any)
		{
			return null;
		}
		try
		{
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			ImageIO.write(image, "png", out);
			return out.toByteArray();
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
	}

	private static int at(Map<Long, byte[]> squares, int plane, int wx, int wy)
	{
		byte[] square = squares.get(key(plane, wx >> 6, wy >> 6));
		return square == null ? 0 : square[(wy & 63) * 64 + (wx & 63)];
	}

	private static long key(int plane, int x, int y)
	{
		return ((long) plane << 40) | ((long) (x & 0xFFFFF) << 20) | (y & 0xFFFFF);
	}

	private static int plane(long key)
	{
		return (int) (key >>> 40);
	}

	private static int sx(long key)
	{
		return (int) ((key >>> 20) & 0xFFFFF);
	}

	private static int sy(long key)
	{
		return (int) (key & 0xFFFFF);
	}
}
