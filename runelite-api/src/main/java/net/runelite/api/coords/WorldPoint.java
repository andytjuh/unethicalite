/*
 * Copyright (c) 2018 Abex
 * Copyright (c) 2017, Adam <Adam@sigterm.info>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.api.coords;

import java.awt.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import lombok.Value;
import net.runelite.api.Client;
import static net.runelite.api.Constants.CHUNK_SIZE;
import static net.runelite.api.Constants.REGION_SIZE;

import net.runelite.api.Locatable;
import net.runelite.api.Perspective;
import net.runelite.api.Tile;

/**
 * A three-dimensional point representing the coordinate of a Tile.
 * <p>
 * WorldPoints are immutable. Methods that modify the properties create a new
 * instance.
 */
@Value
public class WorldPoint implements net.unethicalite.api.Positionable
{
	private static final int[] REGION_MIRRORS = {
		// Prifddinas
		12894, 8755,
		12895, 8756,
		13150, 9011,
		13151, 9012
	};

	/**
	 * X-axis coordinate.
	 */
	private final int x;

	/**
	 * Y-axis coordinate.
	 */
	private final int y;

	/**
	 * The plane level of the Tile, also referred as z-axis coordinate.
	 *
	 * @see Client#getPlane()
	 */
	private final int plane;

	/**
	 * Offsets the x-axis coordinate by the passed value.
	 *
	 * @param dx the offset
	 * @return new instance
	 */
	public WorldPoint dx(int dx)
	{
		return new WorldPoint(x + dx, y, plane);
	}

	/**
	 * Offsets the y-axis coordinate by the passed value.
	 *
	 * @param dy the offset
	 * @return new instance
	 */
	public WorldPoint dy(int dy)
	{
		return new WorldPoint(x, y + dy, plane);
	}

	/**
	 * Offsets the plane by the passed value.
	 *
	 * @param dz the offset
	 * @return new instance
	 */
	public WorldPoint dz(int dz)
	{
		return new WorldPoint(x, y, plane + dz);
	}

	/**
	 * Checks whether a tile is located in the current scene.
	 *
	 * @param client the client
	 * @param x      the tiles x coordinate
	 * @param y      the tiles y coordinate
	 * @return true if the tile is in the scene, false otherwise
	 */
	public static boolean isInScene(Client client, int x, int y)
	{
		int baseX = client.getBaseX();
		int baseY = client.getBaseY();

		int maxX = baseX + Perspective.SCENE_SIZE;
		int maxY = baseY + Perspective.SCENE_SIZE;

		return x >= baseX && x < maxX && y >= baseY && y < maxY;
	}

	/**
	 * Checks whether this tile is located in the current scene.
	 *
	 * @param client the client
	 * @return true if this tile is in the scene, false otherwise
	 */
	public boolean isInScene(Client client)
	{
		return client.getPlane() == plane && isInScene(client, x, y);
	}

	/**
	 * Gets the coordinate of the tile that contains the passed local point.
	 *
	 * @param client the client
	 * @param local  the local coordinate
	 * @return the tile coordinate containing the local point
	 */
	public static WorldPoint fromLocal(Client client, LocalPoint local)
	{
		return fromLocal(client, local.getX(), local.getY(), client.getPlane());
	}

	/**
	 * Gets the coordinate of the tile that contains the passed local point.
	 *
	 * @param client the client
	 * @param x      the local x-axis coordinate
	 * @param y      the local x-axis coordinate
	 * @param plane  the plane
	 * @return the tile coordinate containing the local point
	 */
	public static WorldPoint fromLocal(Client client, int x, int y, int plane)
	{
		return new WorldPoint(
			(x >>> Perspective.LOCAL_COORD_BITS) + client.getBaseX(),
			(y >>> Perspective.LOCAL_COORD_BITS) + client.getBaseY(),
			plane
		);
	}

	/**
	 * Gets the coordinate of the tile that contains the passed local point,
	 * accounting for instances.
	 *
	 * @param client the client
	 * @param localPoint the local coordinate
	 * @return the tile coordinate containing the local point
	 */
	public static WorldPoint fromLocalInstance(Client client, LocalPoint localPoint)
	{
		return fromLocalInstance(client, localPoint, client.getPlane());
	}

	/**
	 * Gets the coordinate of the tile that contains the passed local point,
	 * accounting for instances.
	 *
	 * @param client the client
	 * @param localPoint the local coordinate
	 * @param plane the plane for the returned point, if it is not an instance
	 * @return the tile coordinate containing the local point
	 */
	public static WorldPoint fromLocalInstance(Client client, LocalPoint localPoint, int plane)
	{
		if (client.isInInstancedRegion())
		{
			// get position in the scene
			int sceneX = localPoint.getSceneX();
			int sceneY = localPoint.getSceneY();

			// get chunk from scene
			int chunkX = sceneX / CHUNK_SIZE;
			int chunkY = sceneY / CHUNK_SIZE;

			// get the template chunk for the chunk
			int[][][] instanceTemplateChunks = client.getInstanceTemplateChunks();
			int templateChunk = instanceTemplateChunks[plane][chunkX][chunkY];

			int rotation = templateChunk >> 1 & 0x3;
			int templateChunkY = (templateChunk >> 3 & 0x7FF) * CHUNK_SIZE;
			int templateChunkX = (templateChunk >> 14 & 0x3FF) * CHUNK_SIZE;
			int templateChunkPlane = templateChunk >> 24 & 0x3;

			// calculate world point of the template
			int x = templateChunkX + (sceneX & (CHUNK_SIZE - 1));
			int y = templateChunkY + (sceneY & (CHUNK_SIZE - 1));

			// create and rotate point back to 0, to match with template
			return rotate(new WorldPoint(x, y, templateChunkPlane), 4 - rotation);
		}
		else
		{
			return fromLocal(client, localPoint.getX(), localPoint.getY(), plane);
		}
	}

	/**
	 * Get occurrences of a tile on the scene, accounting for instances. There may be
	 * more than one if the same template chunk occurs more than once on the scene.
	 *
	 * @param client
	 * @param worldPoint
	 * @return
	 */
	public static Collection<WorldPoint> toLocalInstance(Client client, WorldPoint worldPoint)
	{
		if (!client.isInInstancedRegion())
		{
			return Collections.singleton(worldPoint);
		}

		// find instance chunks using the template point. there might be more than one.
		List<WorldPoint> worldPoints = new ArrayList<>();
		int[][][] instanceTemplateChunks = client.getInstanceTemplateChunks();
		for (int z = 0; z < instanceTemplateChunks.length; z++)
		{
			for (int x = 0; x < instanceTemplateChunks[z].length; ++x)
			{
				for (int y = 0; y < instanceTemplateChunks[z][x].length; ++y)
				{
					int chunkData = instanceTemplateChunks[z][x][y];
					int rotation = chunkData >> 1 & 0x3;
					int templateChunkY = (chunkData >> 3 & 0x7FF) * CHUNK_SIZE;
					int templateChunkX = (chunkData >> 14 & 0x3FF) * CHUNK_SIZE;
					int plane = chunkData >> 24 & 0x3;
					if (worldPoint.getX() >= templateChunkX && worldPoint.getX() < templateChunkX + CHUNK_SIZE
						&& worldPoint.getY() >= templateChunkY && worldPoint.getY() < templateChunkY + CHUNK_SIZE
						&& plane == worldPoint.getPlane())
					{
						WorldPoint p = new WorldPoint(client.getBaseX() + x * CHUNK_SIZE + (worldPoint.getX() & (CHUNK_SIZE - 1)),
							client.getBaseY() + y * CHUNK_SIZE + (worldPoint.getY() & (CHUNK_SIZE - 1)),
							z);
						p = rotate(p, rotation);
						worldPoints.add(p);
					}
				}
			}
		}
		return worldPoints;
	}

	/**
	 * Converts a WorldPoint to a 1x1 WorldArea.
	 *
	 * @return Returns a 1x1 WorldArea
	 */
	public WorldArea toWorldArea()
	{
		return new WorldArea(x, y, 1, 1, plane);
	}

	/**
	 * Rotate the coordinates in the chunk according to chunk rotation
	 *
	 * @param point    point
	 * @param rotation rotation
	 * @return world point
	 */
	private static WorldPoint rotate(WorldPoint point, int rotation)
	{
		int chunkX = point.getX() & ~(CHUNK_SIZE - 1);
		int chunkY = point.getY() & ~(CHUNK_SIZE - 1);
		int x = point.getX() & (CHUNK_SIZE - 1);
		int y = point.getY() & (CHUNK_SIZE - 1);
		switch (rotation)
		{
			case 1:
				return new WorldPoint(chunkX + y, chunkY + (CHUNK_SIZE - 1 - x), point.getPlane());
			case 2:
				return new WorldPoint(chunkX + (CHUNK_SIZE - 1 - x), chunkY + (CHUNK_SIZE - 1 - y), point.getPlane());
			case 3:
				return new WorldPoint(chunkX + (CHUNK_SIZE - 1 - y), chunkY + x, point.getPlane());
		}
		return point;
	}

	/**
	 * Gets the shortest distance from this point to a WorldArea.
	 *
	 * @param other the world area
	 * @return the shortest distance
	 */
	public int distanceTo(WorldArea other)
	{
		return new WorldArea(this, 1, 1).distanceTo(other);
	}

	/**
	 * Gets the distance between this point and another.
	 * <p>
	 * If the other point is not on the same plane, this method will return
	 * {@link Integer#MAX_VALUE}. If ignoring the plane is wanted, use the
	 * {@link #distanceTo2D(WorldPoint)} method.
	 *
	 * @param other other point
	 * @return the distance
	 */
	public int distanceTo(WorldPoint other)
	{
		if (other.plane != plane)
		{
			return Integer.MAX_VALUE;
		}

		return distanceTo2D(other);
	}

	/**
	 * Find the distance from this point to another point.
	 * <p>
	 * This method disregards the plane value of the two tiles and returns
	 * the simple distance between the X-Z coordinate pairs.
	 *
	 * @param other other point
	 * @return the distance
	 */
	public int distanceTo2D(WorldPoint other)
	{
		return Math.max(Math.abs(getX() - other.getX()), Math.abs(getY() - other.getY()));
	}

	/**
	 * Gets the straight-line distance between this point and another.
	 * <p>
	 * If the other point is not on the same plane, this method will return
	 * {@link Float#MAX_VALUE}. If ignoring the plane is wanted, use the
	 * {@link #distanceTo2DHypotenuse(WorldPoint)} method.
	 *
	 * @param other other point
	 * @return the straight-line distance
	 */
	public float distanceToHypotenuse(WorldPoint other)
	{
		if (other.plane != plane)
		{
			return Float.MAX_VALUE;
		}

		return distanceTo2DHypotenuse(other);
	}

	/**
	 * Find the straight-line distance from this point to another point.
	 * <p>
	 * This method disregards the plane value of the two tiles and returns
	 * the simple distance between the X-Z coordinate pairs.
	 *
	 * @param other other point
	 * @return the straight-line distance
	 */
	public float distanceTo2DHypotenuse(WorldPoint other)
	{
		return (float) Math.hypot(getX() - other.getX(), getY() - other.getY());
	}

	/**
	 * Converts the passed scene coordinates to a world space
	 */
	public static WorldPoint fromScene(Client client, int x, int y, int plane)
	{
		return new WorldPoint(
			x + client.getBaseX(),
			y + client.getBaseY(),
			plane
		);
	}

	/**
	 * Gets the ID of the region containing this tile.
	 *
	 * @return the region ID
	 */
	public int getRegionID()
	{
		return ((x >> 6) << 8) | (y >> 6);
	}

	/**
	 * Checks if user in within certain zone specified by upper and lower bound
	 *
	 * @param lowerBound
	 * @param upperBound
	 * @param userLocation
	 * @return
	 */
	public static boolean isInZone(WorldPoint lowerBound, WorldPoint upperBound, WorldPoint userLocation)
	{
		return userLocation.getX() >= lowerBound.getX()
			&& userLocation.getX() <= upperBound.getX()
			&& userLocation.getY() >= lowerBound.getY()
			&& userLocation.getY() <= upperBound.getY()
			&& userLocation.getPlane() >= lowerBound.getPlane()
			&& userLocation.getPlane() <= upperBound.getPlane();
	}

	/**
	 * Converts the passed region ID and coordinates to a world coordinate
	 */
	public static WorldPoint fromRegion(int regionId, int regionX, int regionY, int plane)
	{
		return new WorldPoint(
			((regionId >>> 8) << 6) + regionX,
			((regionId & 0xff) << 6) + regionY,
			plane);
	}

	/**
	 * Gets the X-axis coordinate of the region coordinate
	 */
	public int getRegionX()
	{
		return getRegionOffset(x);
	}

	/**
	 * Gets the Y-axis coordinate of the region coordinate
	 */
	public int getRegionY()
	{
		return getRegionOffset(y);
	}

	private static int getRegionOffset(final int position)
	{
		return position & (REGION_SIZE - 1);
	}

	/**
	 * Determine the checkpoint tiles of a server-sided path from this WorldPoint to another WorldPoint.
	 * <p>
	 * The checkpoint tiles of a path are the "corner tiles" of a path and determine the path completely.
	 *
	 * Note that true server-sided pathfinding uses collisiondata of the 128x128 area around this WorldPoint,
	 * while the client only has access to collisiondata within the 104x104 loaded area.
	 * This means that the results would differ in case the server's path goes near (or over) the border of the loaded area.
	 *
	 * @param client The client to compare in
	 * @param other The other WorldPoint to compare with
	 * @return Returns the checkpoint tiles of the path
	 */
	public List<WorldPoint> pathTo(Client client, WorldPoint other)
	{
		if (plane != other.getPlane())
		{
			return null;
		}

		LocalPoint sourceLp = LocalPoint.fromWorld(client, x, y);
		LocalPoint targetLp = LocalPoint.fromWorld(client, other.getX(), other.getY());
		if (sourceLp == null || targetLp == null)
		{
			return null;
		}

		int thisX = sourceLp.getSceneX();
		int thisY = sourceLp.getSceneY();
		int otherX = targetLp.getSceneX();
		int otherY = targetLp.getSceneY();

		Tile[][][] tiles = client.getScene().getTiles();
		Tile sourceTile = tiles[plane][thisX][thisY];

		Tile targetTile = tiles[plane][otherX][otherY];
		List<Tile> checkpointTiles = sourceTile.pathTo(targetTile);
		if (checkpointTiles == null)
		{
			return null;
		}
		List<WorldPoint> checkpointWPs = new ArrayList<>();
		for (Tile checkpointTile : checkpointTiles)
		{
			if (checkpointTile == null)
			{
				break;
			}
			checkpointWPs.add(checkpointTile.getWorldLocation());
		}
		return checkpointWPs;
	}

	/**
	 * Gets the path distance from this point to a WorldPoint.
	 * <p>
	 * If the other point is unreachable, this method will return {@link Integer#MAX_VALUE}.
	 *
	 * @param client
	 * @param other
	 * @return Returns the path distance
	 */
	public int distanceToPath(Client client, WorldPoint other)
	{
		List<WorldPoint> checkpointWPs = this.pathTo(client, other);
		if (checkpointWPs == null)
		{
			// No path found
			return Integer.MAX_VALUE;
		}

		WorldPoint destinationPoint = checkpointWPs.get(checkpointWPs.size() - 1);
		if (other.getX() != destinationPoint.getX() || other.getY() != destinationPoint.getY())
		{
			// Path found but not to the requested tile
			return Integer.MAX_VALUE;
		}
		WorldPoint Point1 = this;
		int distance = 0;
		for (WorldPoint Point2 : checkpointWPs)
		{
			distance += Point1.distanceTo2D(Point2);
			Point1 = Point2;
		}
		return distance;
	}

	/**
	 * Translate a coordinate either between overworld and real, or real and overworld
	 *
	 * @param worldPoint
	 * @param toOverworld whether to convert to overworld coordinates, or to real coordinates
	 * @return
	 */
	public static WorldPoint getMirrorPoint(WorldPoint worldPoint, boolean toOverworld)
	{
		int region = worldPoint.getRegionID();
		for (int i = 0; i < REGION_MIRRORS.length; i += 2)
		{
			int real = REGION_MIRRORS[i];
			int overworld = REGION_MIRRORS[i + 1];

			// Test against what we are converting from
			if (region == (toOverworld ? real : overworld))
			{
				return fromRegion(toOverworld ? overworld : real,
					worldPoint.getRegionX(), worldPoint.getRegionY(), worldPoint.getPlane());
			}
		}
		return worldPoint;
	}

	/**
	 * Checks whether this tile is located within any of the given areas.
	 *
	 * @param worldAreas areas to check within
	 * @return {@code true} if any area contains this point, {@code false} otherwise.
	 */
	public boolean isInArea(WorldArea... worldAreas)
	{
		for (WorldArea area : worldAreas)
		{
			if (area.contains(this))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Checks whether this tile is located within any of the given areas, disregarding any plane differences.
	 *
	 * @param worldAreas areas to check within
	 * @return {@code true} if any area contains this point, {@code false} otherwise.
	 */
	public boolean isInArea2D(WorldArea... worldAreas)
	{
		for (WorldArea area : worldAreas)
		{
			if (area.contains2D(this))
			{
				return true;
			}
		}
		return false;
	}

	public void outline(Client client, Graphics2D graphics2D, Color color)
	{
		outline(client, graphics2D, color, null);
	}

	public void outline(Client client, Graphics2D graphics, Color color, String text)
	{
		LocalPoint localPoint = LocalPoint.fromWorld(client, this);
		if (localPoint == null)
		{
			return;
		}

		Polygon poly = Perspective.getCanvasTilePoly(client, localPoint);
		if (poly == null)
		{
			return;
		}

		if (text != null)
		{
			var stringX = (int) (poly.getBounds().getCenterX() -
					graphics.getFont().getStringBounds(text, graphics.getFontRenderContext()).getWidth() / 2);
			var stringY = (int) poly.getBounds().getCenterY();
			graphics.setColor(color);
			graphics.drawString(text, stringX, stringY);
		}

		graphics.setColor(color);
		final Stroke originalStroke = graphics.getStroke();
		graphics.setStroke(new BasicStroke(2));
		graphics.draw(poly);
		graphics.setColor(new Color(0, 0, 0, 50));
		graphics.fill(poly);
		graphics.setStroke(originalStroke);
	}

	public int distanceTo(Locatable locatable)
	{
		return locatable.getWorldLocation().distanceTo(this);
	}

	public WorldArea createWorldArea(int width, int height)
	{
		return new WorldArea(this, width, height);
	}
}
