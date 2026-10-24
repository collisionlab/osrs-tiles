package mapstyle.routing;

import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import javax.imageio.ImageIO;

/**
 * The pathfinder's blocked ground as a tile pyramid, in the same layout as {@link ComponentTiles}:
 * every tile of a collision-bearing square that isn't walkable is {@link #RGB} red, walkable ground
 * transparent, so a viewer shows collision at any zoom without reading a square. Zoomed out, where
 * a pixel covers several game tiles, its alpha says how many of them are blocked, in four steps.
 * Squares with no collision (no file) are left transparent: they are no place at all.
 */
public final class BlockedTiles
{
	/** The planner's collision colour. */
	public static final int RGB = 0xEB263A;
	/** Alpha for a quarter, half, three quarters and all of a pixel's game tiles blocked. */
	public static final int[] ALPHA = {70, 105, 125, 140};

	private static final IndexColorModel MODEL = model();

	private BlockedTiles()
	{
	}

	private static IndexColorModel model()
	{
		int n = ALPHA.length + 1;
		byte[] r = new byte[n], g = new byte[n], b = new byte[n], a = new byte[n];
		for (int i = 1; i < n; i++)
		{
			r[i] = (byte) (RGB >> 16);
			g[i] = (byte) (RGB >> 8);
			b[i] = (byte) RGB;
			a[i] = (byte) ALPHA[i - 1];
		}
		return new IndexColorModel(4, n, r, g, b, a);
	}

	/** Every tile of the pyramid with a blocked tile in it, by path (`blocked/[p1/]z/x/y.png`). */
	public static SortedMap<String, byte[]> tiles(RoutingLayers.Export export)
	{
		// 1 per blocked tile, by plane and map square.
		Map<Long, byte[]> squares = new HashMap<>();
		for (var entry : export.squares.entrySet())
		{
			String[] parts = entry.getKey().replace(".bin", "").split("/");
			int plane = Integer.parseInt(parts[0].substring(1)), sx = Integer.parseInt(parts[1]), sy = Integer.parseInt(parts[2]);
			if (sx < ComponentTiles.ORIGIN_X || sy < ComponentTiles.ORIGIN_Y)
			{
				continue;
			}
			byte[] status = entry.getValue().status, blocked = new byte[4096];
			for (int i = 0; i < 4096; i++)
			{
				blocked[i] = (byte) (status[i] == RoutingLayers.NON_WALKABLE ? 1 : 0);
			}
			squares.put(key(plane, sx, sy), blocked);
		}
		SortedMap<String, byte[]> files = new TreeMap<>();
		for (int z = ComponentTiles.NATIVE_ZOOM; z >= ComponentTiles.MIN_ZOOM; z--)
		{
			int span = 1 << (ComponentTiles.NATIVE_ZOOM - z);
			Set<Long> wanted = new HashSet<>();
			for (long square : squares.keySet())
			{
				wanted.add(key(plane(square), Math.floorDiv(sx(square) - ComponentTiles.ORIGIN_X, span),
					Math.floorDiv(sy(square) - ComponentTiles.ORIGIN_Y, span)));
			}
			for (long tile : wanted)
			{
				byte[] png = render(squares, plane(tile), sx(tile), sy(tile), z);
				if (png != null)
				{
					int plane = plane(tile);
					files.put("blocked/" + (plane == 0 ? "" : "p" + plane + "/") + z + "/" + sx(tile) + "/" + sy(tile) + ".png", png);
				}
			}
		}
		return files;
	}

	/** Tile (c, r) at zoom z, or null when nothing in it is blocked. */
	private static byte[] render(Map<Long, byte[]> squares, int plane, int c, int r, int z)
	{
		int size = ComponentTiles.SIZE, span = 1 << (ComponentTiles.NATIVE_ZOOM - z);
		double perPixel = (double) (64 * span) / size;
		int x0 = (ComponentTiles.ORIGIN_X + c * span) * 64, y0 = (ComponentTiles.ORIGIN_Y + r * span) * 64;
		BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_BYTE_BINARY, MODEL);
		var raster = image.getRaster();
		boolean any = false;
		int block = Math.max(1, (int) perPixel);
		for (int py = 0; py < size; py++)
		{
			for (int px = 0; px < size; px++)
			{
				int level;
				if (block == 1)
				{
					int wx = x0 + (int) Math.floor((px + 0.5) * perPixel), wy = y0 + (int) Math.floor((size - py - 0.5) * perPixel);
					level = at(squares, plane, wx, wy) == 1 ? ALPHA.length : 0;
				}
				else
				{
					// The share of the pixel's game tiles blocked, among those with collision at all.
					int bx = x0 + px * block, by = y0 + (size - py - 1) * block, blocked = 0, known = 0;
					for (int dy = 0; dy < block; dy++)
					{
						for (int dx = 0; dx < block; dx++)
						{
							int value = at(squares, plane, bx + dx, by + dy);
							if (value >= 0)
							{
								known++;
								blocked += value;
							}
						}
					}
					level = blocked == 0 ? 0 : (int) Math.ceil((double) ALPHA.length * blocked / known);
				}
				if (level != 0)
				{
					raster.setSample(px, py, 0, level);
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

	/** 1 blocked, 0 walkable, -1 where the square has no collision. */
	private static int at(Map<Long, byte[]> squares, int plane, int wx, int wy)
	{
		byte[] square = squares.get(key(plane, wx >> 6, wy >> 6));
		return square == null ? -1 : square[(wy & 63) * 64 + (wx & 63)];
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
