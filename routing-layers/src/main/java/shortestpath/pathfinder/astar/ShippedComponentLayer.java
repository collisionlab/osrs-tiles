package shortestpath.pathfinder.astar;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import shortestpath.pathfinder.CollisionMap;

/**
 * The plugin routes on the routing-components.bin it ships, which its builder generates from the collision
 * map and the cuts. The layers are drawn from that builder over the same map and cuts, so they are
 * production's graph only while the two agree; the plugin's RoutingComponentsFreshnessTest requires the
 * same. In this package because the layer and its writer are package-private.
 */
public final class ShippedComponentLayer
{
	private ShippedComponentLayer()
	{
	}

	public static void verify(CollisionMap collision, RoutingCuts cuts) throws IOException
	{
		byte[] shipped;
		try (InputStream in = ComponentLayer.class.getResourceAsStream(ComponentLayer.RESOURCE))
		{
			if (in == null)
			{
				throw new IOException("missing production resource " + ComponentLayer.RESOURCE);
			}
			shipped = in.readAllBytes();
		}
		verify(shipped, ComponentLayerWriter.write(RoutingStaticBuilder.generate(collision, cuts.pairs())));
	}

	static void verify(byte[] shipped, byte[] generated)
	{
		if (!Arrays.equals(shipped, generated))
		{
			throw new IllegalStateException("the plugin's routing-components.bin (" + shipped.length + " bytes) differs "
				+ "from the one its collision map and routing cuts generate (" + generated.length + " bytes)");
		}
	}
}
