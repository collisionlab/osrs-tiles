package mapstyle;

import com.google.gson.GsonBuilder;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import javax.imageio.ImageIO;
import net.runelite.cache.MapImageDumper;
import net.runelite.cache.ObjectManager;
import net.runelite.cache.fs.Store;
import net.runelite.cache.region.Region;
import net.runelite.cache.region.RegionLoader;
import net.runelite.cache.util.XteaKeyManager;

/**
 * Renders a small rectangle of the world map straight from a cache, once
 * per requested style, so a styling change can be compared against the
 * RuneLite reference without rendering the whole world.
 *
 * <pre>
 * ./gradlew -p map-style run --args='--cache cache --xteas keys.json
 *     --area 3200,3200,3263,3263 --plane 0 --styles runelite,muted --height
 *     --out build/map-style/lumbridge'
 * </pre>
 *
 * Writes {@code <style>.png} per style, {@code height.png} with
 * {@code --height}, {@code compare.png} (all of them side by side) and
 * {@code manifest.json} recording the inputs and output hashes.
 */
public final class RenderRegions
{
	private static final int MAP_SCALE = 4; // MapImageDumper's pixels per tile
	private static final int GUTTER = 8;

	private RenderRegions()
	{
	}

	public static void main(String[] argv) throws Exception
	{
		Args args;
		try
		{
			args = Args.parse(argv);
		}
		catch (IllegalArgumentException e)
		{
			System.err.println("error: " + e.getMessage());
			System.err.println(Args.USAGE);
			System.exit(2);
			return;
		}

		Path out = args.out.toPath();
		Files.createDirectories(out);

		XteaKeyManager keys = new XteaKeyManager();
		try (InputStream in = new FileInputStream(args.xteas))
		{
			keys.loadKeys(in);
		}

		Map<String, Object> manifest = new LinkedHashMap<>();
		manifest.put("cache", args.cache.getCanonicalPath());
		manifest.put("cacheIdx255Sha256", sha256(new File(args.cache, "main_file_cache.idx255")));
		manifest.put("xteas", args.xteas.getCanonicalPath());
		manifest.put("xteasSha256", sha256(args.xteas));
		manifest.put("runeliteCache", runeliteCacheVersion());
		manifest.put("area", new int[]{args.x0, args.y0, args.x1, args.y1});
		manifest.put("plane", args.plane);
		manifest.put("renderedRegions", new ArrayList<>(args.regionsWithMargin()));
		Map<String, Object> outputs = new LinkedHashMap<>();
		manifest.put("outputs", outputs);

		List<BufferedImage> panels = new ArrayList<>();
		try (Store store = new Store(args.cache))
		{
			store.load();
			ObjectManager objects = new ObjectManager(store);
			objects.load();

			for (Styles style : args.styles)
			{
				long start = System.nanoTime();
				// Fresh regions per style: MapStyle.keep edits their location lists.
				RegionLoader regions = loadRegions(store, keys, args);
				filterLocations(regions, objects, style.style);

				BufferedImage full;
				if (style.forked)
				{
					StyledMapImageDumper dumper = new StyledMapImageDumper(store, regions);
					dumper.setStyle(style.style);
					dumper.setLowMemory(false);
					dumper.setRenderLabels(args.labels);
					full = dumper.load().drawMap(args.plane);
				}
				else
				{
					MapImageDumper dumper = new MapImageDumper(store, regions);
					dumper.setLowMemory(false);
					dumper.setRenderLabels(args.labels);
					full = dumper.load().drawMap(args.plane);
				}

				BufferedImage image = crop(full, regions, args);
				File file = out.resolve(style.id() + ".png").toFile();
				ImageIO.write(image, "png", file);
				outputs.put(file.getName(), outputEntry(file, style.forked ? "StyledMapImageDumper" : "MapImageDumper"));
				panels.add(image);
				System.out.printf("%-10s %s (%d ms)%n", style.id(), file, (System.nanoTime() - start) / 1_000_000);
			}

			if (args.height)
			{
				RegionLoader regions = loadRegions(store, keys, args);
				int[] range = new int[2];
				BufferedImage image = heightImage(regions, args, range);
				File file = out.resolve("height.png").toFile();
				ImageIO.write(image, "png", file);
				Map<String, Object> entry = outputEntry(file, "height");
				// Raw RuneLite heights: more negative = higher ground.
				entry.put("heightRange", range);
				outputs.put(file.getName(), entry);
				panels.add(image);
				System.out.printf("%-10s %s (heights %d..%d)%n", "height", file, range[0], range[1]);
			}
		}

		if (panels.size() > 1)
		{
			File file = out.resolve("compare.png").toFile();
			ImageIO.write(sideBySide(panels), "png", file);
			outputs.put(file.getName(), outputEntry(file, "compare"));
		}

		Files.write(out.resolve("manifest.json"),
			(new GsonBuilder().setPrettyPrinting().create().toJson(manifest) + "\n").getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Loads the requested regions plus a one-region margin: RuneLite blends
	 * underlays across a 5-tile window and draws objects that overhang from
	 * neighbours, so the margin keeps the area's edges identical to a
	 * full-world render. The margin is cropped away afterwards.
	 */
	private static RegionLoader loadRegions(Store store, XteaKeyManager keys, Args args) throws IOException
	{
		RegionLoader loader = new RegionLoader(store, keys);
		for (int id : args.regionsWithMargin())
		{
			loader.loadRegionFromArchive(id);
		}
		if (loader.getRegions().isEmpty())
		{
			throw new IllegalStateException("no map regions found for area " + args.areaString());
		}
		return loader;
	}

	private static void filterLocations(RegionLoader regions, ObjectManager objects, MapStyle style)
	{
		for (Region region : regions.getRegions())
		{
			region.getLocations().removeIf(loc -> !style.keep(loc, objects.getObject(loc.getId())));
		}
	}

	/** Cuts the requested area out of an image spanning the loaded regions' bounds. */
	private static BufferedImage crop(BufferedImage full, RegionLoader regions, Args args)
	{
		// drawMap() has already called calculateBounds(); pixel row 0 is the
		// northern edge of the highest region.
		int minX = regions.getLowestX().getBaseX();
		int maxY = regions.getHighestY().getBaseY() + Region.Y;
		int px = (args.x0 - minX) * MAP_SCALE;
		int py = (maxY - 1 - args.y1) * MAP_SCALE;
		int w = (args.x1 - args.x0 + 1) * MAP_SCALE;
		int h = (args.y1 - args.y0 + 1) * MAP_SCALE;
		if (px < 0 || py < 0 || px + w > full.getWidth() || py + h > full.getHeight())
		{
			throw new IllegalStateException("area " + args.areaString() + " extends past the cache's map regions");
		}
		BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.drawImage(full.getSubimage(px, py, w, h), 0, 0, null);
		g.dispose();
		return image;
	}

	/** Grey ramp over the area's own height range, white = highest ground. */
	private static BufferedImage heightImage(RegionLoader regions, Args args, int[] range)
	{
		int w = args.x1 - args.x0 + 1;
		int h = args.y1 - args.y0 + 1;
		int[][] heights = new int[w][h];
		int lo = Integer.MAX_VALUE;
		int hi = Integer.MIN_VALUE;
		for (int x = 0; x < w; x++)
		{
			for (int y = 0; y < h; y++)
			{
				Region r = regions.findRegionForWorldCoordinates(args.x0 + x, args.y0 + y);
				int v = r == null ? 0 : r.getTileHeight(args.plane, (args.x0 + x) & 63, (args.y0 + y) & 63);
				heights[x][y] = v;
				lo = Math.min(lo, v);
				hi = Math.max(hi, v);
			}
		}
		range[0] = lo;
		range[1] = hi;

		BufferedImage image = new BufferedImage(w * MAP_SCALE, h * MAP_SCALE, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		for (int x = 0; x < w; x++)
		{
			for (int y = 0; y < h; y++)
			{
				float t = hi == lo ? 0f : (float) (hi - heights[x][y]) / (hi - lo);
				g.setColor(new Color(t, t, t));
				g.fillRect(x * MAP_SCALE, (h - 1 - y) * MAP_SCALE, MAP_SCALE, MAP_SCALE);
			}
		}
		g.dispose();
		return image;
	}

	private static BufferedImage sideBySide(List<BufferedImage> panels)
	{
		int w = -GUTTER;
		int h = 0;
		for (BufferedImage p : panels)
		{
			w += p.getWidth() + GUTTER;
			h = Math.max(h, p.getHeight());
		}
		BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setColor(Color.WHITE);
		g.fillRect(0, 0, w, h);
		int x = 0;
		for (BufferedImage p : panels)
		{
			g.drawImage(p, x, 0, null);
			x += p.getWidth() + GUTTER;
		}
		g.dispose();
		return image;
	}

	private static Map<String, Object> outputEntry(File file, String renderer) throws IOException
	{
		Map<String, Object> entry = new LinkedHashMap<>();
		entry.put("renderer", renderer);
		entry.put("sha256", sha256(file));
		return entry;
	}

	/** The net.runelite:cache version on the classpath, from its jar name (cache-1.13.2.jar). */
	private static String runeliteCacheVersion()
	{
		String jar = new File(MapImageDumper.class.getProtectionDomain().getCodeSource().getLocation().getPath()).getName();
		return jar.matches("cache-.+\\.jar") ? jar.substring("cache-".length(), jar.length() - ".jar".length()) : jar;
	}

	private static String sha256(File file) throws IOException
	{
		try
		{
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			try (InputStream in = new FileInputStream(file))
			{
				byte[] buf = new byte[1 << 16];
				for (int n; (n = in.read(buf)) > 0; )
				{
					md.update(buf, 0, n);
				}
			}
			StringBuilder sb = new StringBuilder();
			for (byte b : md.digest())
			{
				sb.append(String.format("%02x", b));
			}
			return sb.toString();
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IllegalStateException(e);
		}
	}

	private static final class Args
	{
		static final String USAGE = String.join("\n",
			"usage: RenderRegions --cache DIR --xteas FILE --out DIR",
			"                     (--area X0,Y0,X1,Y1 | --regions ID[,ID...])",
			"                     [--plane Z] [--styles S[,S...]] [--height] [--labels]",
			"  --area     inclusive world-tile rectangle",
			"  --regions  map-square ids ((x>>6)<<8 | (y>>6)); renders their union",
			"  --styles   runelite (default), standard, declutter, muted");

		File cache;
		File xteas;
		File out;
		int x0;
		int y0;
		int x1;
		int y1;
		int plane;
		List<Styles> styles = new ArrayList<>(List.of(Styles.RUNELITE));
		boolean height;
		boolean labels;

		static Args parse(String[] argv)
		{
			Args a = new Args();
			boolean haveArea = false;
			for (int i = 0; i < argv.length; i++)
			{
				String flag = argv[i];
				switch (flag)
				{
					case "--height":
						a.height = true;
						continue;
					case "--labels":
						a.labels = true;
						continue;
					default:
						break;
				}
				if (i + 1 >= argv.length)
				{
					throw new IllegalArgumentException(flag + " needs a value");
				}
				String value = argv[++i];
				switch (flag)
				{
					case "--cache":
						a.cache = new File(value);
						break;
					case "--xteas":
						a.xteas = new File(value);
						break;
					case "--out":
						a.out = new File(value);
						break;
					case "--plane":
						a.plane = Integer.parseInt(value);
						break;
					case "--styles":
						a.styles.clear();
						for (String s : value.split(","))
						{
							a.styles.add(Styles.parse(s));
						}
						break;
					case "--area":
					{
						int[] v = ints(value, 4, flag);
						a.x0 = Math.min(v[0], v[2]);
						a.y0 = Math.min(v[1], v[3]);
						a.x1 = Math.max(v[0], v[2]);
						a.y1 = Math.max(v[1], v[3]);
						haveArea = true;
						break;
					}
					case "--regions":
					{
						a.x0 = a.y0 = Integer.MAX_VALUE;
						a.x1 = a.y1 = Integer.MIN_VALUE;
						for (int id : ints(value, -1, flag))
						{
							int bx = (id >> 8) << 6;
							int by = (id & 0xFF) << 6;
							a.x0 = Math.min(a.x0, bx);
							a.y0 = Math.min(a.y0, by);
							a.x1 = Math.max(a.x1, bx + Region.X - 1);
							a.y1 = Math.max(a.y1, by + Region.Y - 1);
						}
						haveArea = true;
						break;
					}
					default:
						throw new IllegalArgumentException("unknown flag " + flag);
				}
			}
			if (a.cache == null || a.xteas == null || a.out == null || !haveArea)
			{
				throw new IllegalArgumentException("--cache, --xteas, --out and --area/--regions are required");
			}
			if (!new File(a.cache, "main_file_cache.idx255").isFile())
			{
				throw new IllegalArgumentException("not a cache directory: " + a.cache);
			}
			if (!a.xteas.isFile())
			{
				throw new IllegalArgumentException("no such XTEA file: " + a.xteas);
			}
			if (a.plane < 0 || a.plane >= Region.Z)
			{
				throw new IllegalArgumentException("--plane must be 0.." + (Region.Z - 1));
			}
			return a;
		}

		TreeSet<Integer> regionsWithMargin()
		{
			TreeSet<Integer> ids = new TreeSet<>();
			for (int rx = (x0 >> 6) - 1; rx <= (x1 >> 6) + 1; rx++)
			{
				for (int ry = (y0 >> 6) - 1; ry <= (y1 >> 6) + 1; ry++)
				{
					if (rx >= 0 && ry >= 0 && rx < 256 && ry < 256)
					{
						ids.add(rx << 8 | ry);
					}
				}
			}
			return ids;
		}

		String areaString()
		{
			return x0 + "," + y0 + "," + x1 + "," + y1;
		}

		private static int[] ints(String value, int count, String flag)
		{
			String[] parts = value.split(",");
			if (count >= 0 && parts.length != count)
			{
				throw new IllegalArgumentException(flag + " expects " + count + " comma-separated integers");
			}
			int[] v = new int[parts.length];
			for (int i = 0; i < parts.length; i++)
			{
				v[i] = Integer.parseInt(parts[i].trim());
			}
			return v;
		}
	}
}
