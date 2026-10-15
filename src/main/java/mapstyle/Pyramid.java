package mapstyle;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * Cuts a rendered PNG into a Leaflet {@code CRS.Simple} tile pyramid at
 * {@code <out>/<z>/<x>/<y>.png}. The highest zoom is full resolution and
 * each level below halves it (bilinear), down to one tile. Edge tiles are
 * padded with the background colour so every tile is square.
 *
 * <pre>
 * Pyramid IN.png OUT_DIR [TILE_SIZE=1024] [BACKGROUND=3D7A85]
 * </pre>
 */
public final class Pyramid
{
	private Pyramid()
	{
	}

	public static void main(String[] args) throws IOException
	{
		if (args.length < 2)
		{
			System.err.println("usage: Pyramid IN.png OUT_DIR [TILE_SIZE=1024] [BACKGROUND=3D7A85]");
			System.exit(2);
		}
		BufferedImage level = ImageIO.read(new File(args[0]));
		File out = new File(args[1]);
		int size = args.length > 2 ? Integer.parseInt(args[2]) : 1024;
		Color background = new Color(Integer.parseInt(args.length > 3 ? args[3] : "3D7A85", 16));

		int maxZoom = 0;
		for (int w = Math.max(level.getWidth(), level.getHeight()); w > size; w = (w + 1) / 2)
		{
			maxZoom++;
		}

		int tiles = 0;
		for (int z = maxZoom; z >= 0; z--)
		{
			for (int ty = 0; ty * size < level.getHeight(); ty++)
			{
				for (int tx = 0; tx * size < level.getWidth(); tx++)
				{
					BufferedImage tile = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
					Graphics2D g = tile.createGraphics();
					g.setColor(background);
					g.fillRect(0, 0, size, size);
					int w = Math.min(size, level.getWidth() - tx * size);
					int h = Math.min(size, level.getHeight() - ty * size);
					g.drawImage(level.getSubimage(tx * size, ty * size, w, h), 0, 0, null);
					g.dispose();
					File file = new File(out, z + "/" + tx + "/" + ty + ".png");
					file.getParentFile().mkdirs();
					ImageIO.write(tile, "png", file);
					tiles++;
				}
			}
			if (z > 0)
			{
				level = half(level);
			}
		}
		System.out.printf("%d tiles, zoom 0..%d, %dpx%n", tiles, maxZoom, size);
	}

	private static BufferedImage half(BufferedImage image)
	{
		int w = (image.getWidth() + 1) / 2;
		int h = (image.getHeight() + 1) / 2;
		BufferedImage half = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = half.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		g.drawImage(image, 0, 0, w, h, null);
		g.dispose();
		return half;
	}
}
