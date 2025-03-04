package net.runelite.client.plugins.unethicalite.regions;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.ConfigButtonClicked;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.eventbus.Subscribe;
import net.unethicalite.api.events.PlaneChanged;
import net.unethicalite.api.game.Game;
import net.unethicalite.api.movement.pathfinder.GlobalCollisionMap;
import net.unethicalite.api.movement.pathfinder.Walker;
import net.unethicalite.client.managers.RegionManager;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

@Singleton
@Slf4j
public class RegionHandler
{
	@Inject
	@Named("unethicalite.api.url")
	private String apiUrl;

	@Inject
	private Client client;

	@Inject
	private RegionManager regionManager;

	@Inject
	private GlobalCollisionMap collisionMap;

	@Inject
	private AddTransportDialog transportDialog;

	public static boolean selectingSourceTile = false;
	public static boolean selectingDestinationTile = false;
	public static boolean selectingObject = false;

	@Subscribe
	public void onClientTick(ClientTick e)
	{
		if (selectingSourceTile)
		{
			client.createMenuEntry(-1)
					.setOption("Set")
					.setTarget("<col=00ff00>Source tile")
					.setIdentifier(TileSelection.SOURCE.id);

			return;
		}

		if (selectingDestinationTile)
		{
			client.createMenuEntry(-1)
					.setOption("Set")
					.setTarget("<col=00ff00>Destination tile")
					.setIdentifier(TileSelection.DESTINATION.id);
			return;
		}

		if (selectingObject)
		{
			client.createMenuEntry(-1)
					.setOption("Set")
					.setTarget("<col=00ff00>Transport object")
					.setIdentifier(TileSelection.OBJECT.id);
		}
	}

	@Subscribe
	public void onConfigButtonClicked(ConfigButtonClicked e)
	{
		if (!e.getGroup().equals("unethicalite"))
		{
			return;
		}

		switch (e.getKey())
		{
			case "downloadCollisionData":
				updateCollisionMap();
				break;
			case "localCollisionData":
				loadCachedCollisionMap();
				break;
			case "addTransportData":
				if (transportDialog == null)
				{
					log.error("Add transport UI was not loaded somehow");
					return;
				}

				transportDialog.display();
				break;
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		if (e.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		regionManager.sendRegion();
	}

	@Subscribe
	public void onPlaneChanged(PlaneChanged e)
	{
		if (Game.getState() != GameState.LOGGED_IN)
		{
			return;
		}

		regionManager.sendRegion();
	}

	private void loadCachedCollisionMap()
	{
		try (InputStream is = Walker.class.getResourceAsStream("/regions"))
		{
			if (is == null)
			{
				return;
			}

			collisionMap.overwrite(new GlobalCollisionMap(
					new GZIPInputStream(new ByteArrayInputStream(is.readAllBytes())).readAllBytes()
			));
		}
		catch (IOException e)
		{
			throw new RuntimeException(e);
		}
	}

	private void updateCollisionMap()
	{
//		try (InputStream is = new URL(apiUrl + "/regions").openStream())
//		{
//			collisionMap.overwrite(new GlobalCollisionMap(readGzip(is.readAllBytes())));
//		}
//		catch (IOException e)
//		{
//			log.error("Error downloading collision data: {}", e.getMessage());
//		}
	}

	private byte[] readGzip(byte[] input) throws IOException
	{
		return new GZIPInputStream(new ByteArrayInputStream(input)).readAllBytes();
	}

	enum TileSelection
	{
		SOURCE(-420),
		DESTINATION(-421),
		OBJECT(-422);

		@Getter
		private final int id;

		TileSelection(int id)
		{
			this.id = id;
		}
	}
}
