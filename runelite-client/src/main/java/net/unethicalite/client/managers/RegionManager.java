package net.unethicalite.client.managers;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.unethicalite.api.entities.Players;
import net.unethicalite.api.game.Game;
import net.unethicalite.api.movement.Reachable;
import net.unethicalite.api.movement.pathfinder.model.Transport;
import net.unethicalite.api.movement.pathfinder.Walker;
import net.unethicalite.api.scene.Tiles;
import net.runelite.api.CollisionData;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.GameState;
import net.runelite.api.Tile;
import net.runelite.api.coords.Direction;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.unethicalite.regions.TileFlag;
import net.unethicalite.client.Static;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Singleton
public class RegionManager
{
	public static final MediaType JSON_MEDIATYPE = MediaType.parse("application/json");
	public static final Gson GSON = new GsonBuilder().create();
	private static final Logger logger = LoggerFactory.getLogger(RegionManager.class);

	@Inject
	@Named("unethicalite.api.url")
	private String apiUrl;

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private ScheduledExecutorService executorService;

	public void sendRegion()
	{
		if (Game.getState() != GameState.LOGGED_IN)
		{
			return;
		}

		if (Static.getClient().isInInstancedRegion())
		{
			executorService.schedule(() ->
			{
				Request request = new Request.Builder()
						.get()
						.url(apiUrl + "/regions/instance/" + Players.getLocal().getWorldLocation().getRegionID())
						.build();

				try (Response response = okHttpClient.newCall(request)
						.execute())
				{
					int code = response.code();
					if (code != 200)
					{
						logger.error("Instance store request was unsuccessful: {}", code);
						return;
					}

					logger.debug("Instanced region stored successfully");
				}
				catch (Exception e)
				{
					logger.error("Failed to POST: {}", e.getMessage());
					e.printStackTrace();
				}
			}, 5_000, TimeUnit.MILLISECONDS);

			return;
		}

		CollisionData[] col = Static.getClient().getCollisionMaps();
		if (col == null)
		{
			return;
		}

		List<TileFlag> tileFlags = new ArrayList<>();
		Map<WorldPoint, List<Transport>> transportLinks = Walker.buildTransportLinks();
		int plane = Static.getClient().getPlane();
		CollisionData data = col[plane];
		if (data == null)
		{
			return;
		}

		int[][] flags = data.getFlags();
		for (int x = 0; x < flags.length; x++)
		{
			for (int y = 0; y < flags.length; y++)
			{
				int tileX = x + Static.getClient().getBaseX();
				int tileY = y + Static.getClient().getBaseY();
				int flag = flags[x][y];

				// Stop if we reach any tiles which dont have collision data loaded
				// Usually occurs for tiles which are loaded in the 104x104 scene, but are outside the region
				if (flag == 0xFFFFFF)
				{
					continue;
				}

				int regionId = ((tileX >> 6) << 8) | (tileY >> 6);

				// Set the full block flag in case tiles are null (ex. on upper levels)
				TileFlag tileFlag = new TileFlag(tileX, tileY, plane, CollisionDataFlag.BLOCK_MOVEMENT_FULL, regionId);
				Tile tile = Tiles.getAt(tileX, tileY, plane);
				if (tile == null)
				{
					tileFlags.add(tileFlag);
					continue;
				}

				tileFlag.setFlag(flag);
				WorldPoint tileCoords = tile.getWorldLocation();

				// Check if we are blocked by objects
				// We don't need to parse west/south because they're checked by parsing adjacent tiles for north/east
				// We also skip the current tile if an adjacent tile does not have their flags loaded
				WorldPoint northernTile = tileCoords.dy(1);
				if (Reachable.getCollisionFlag(northernTile) == 0xFFFFFF)
				{
					continue;
				}

				if (Reachable.isObstacle(northernTile)
						&& !Reachable.isWalled(Direction.NORTH, tileFlag.getFlag())
				)
				{
					tileFlag.setFlag(tileFlag.getFlag() + CollisionDataFlag.BLOCK_MOVEMENT_NORTH);
				}

				WorldPoint easternTile = tileCoords.dx(1);
				if (Reachable.getCollisionFlag(easternTile) == 0xFFFFFF)
				{
					continue;
				}

				if (Reachable.isObstacle(easternTile)
						&& !Reachable.isWalled(Direction.EAST, tileFlag.getFlag())
				)
				{
					tileFlag.setFlag(tileFlag.getFlag() + CollisionDataFlag.BLOCK_MOVEMENT_EAST);
				}

				List<Transport> transports = transportLinks.get(tileCoords);
				if (plane == Static.getClient().getPlane())
				{
					for (Direction direction : Direction.values())
					{
						switch (direction)
						{
							case NORTH:
								if ((Reachable.hasDoor(tile, direction) || Reachable.hasDoor(northernTile, Direction.SOUTH))
										&& !isTransport(transports, tileCoords, northernTile))
								{
									tileFlag.setFlag(tileFlag.getFlag() - CollisionDataFlag.BLOCK_MOVEMENT_NORTH);
								}
								break;
							case EAST:
								if ((Reachable.hasDoor(tile, direction) || Reachable.hasDoor(easternTile, Direction.WEST))
										&& !isTransport(transports, tileCoords, easternTile))
								{
									tileFlag.setFlag(tileFlag.getFlag() - CollisionDataFlag.BLOCK_MOVEMENT_EAST);
								}
								break;
						}
					}
				}

				tileFlags.add(tileFlag);
			}
		}

		executorService.schedule(() ->
		{
			String json = GSON.toJson(tileFlags);
			RequestBody body = RequestBody.create(JSON_MEDIATYPE, json);
			Request request = new Request.Builder()
					.post(body)
					.url(apiUrl + "/regions")
					.build();
			try (Response response = okHttpClient.newCall(request)
					.execute())
			{
				int code = response.code();
				if (code != 200)
				{
					logger.error("Request was unsuccessful: {}", code);
					return;
				}

				logger.debug("Region saved successfully");
			}
			catch (Exception e)
			{
				logger.error("Failed to POST: {}", e.getMessage());
				e.printStackTrace();
			}
		}, 5, TimeUnit.SECONDS);
	}

	public boolean isTransport(List<Transport> transports, WorldPoint from, WorldPoint to)
	{
		if (transports == null)
		{
			return false;
		}

		return transports.stream().anyMatch(t -> t.getSource().equals(from) && t.getDestination().equals(to));
	}
}
