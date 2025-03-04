/*
 * Copyright (c) 2016-2017, Adam <Adam@sigterm.info>
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
package net.unethicalite.client.minimal.overlay;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Ints;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.KeyCode;
import net.runelite.api.Varbits;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.FocusChanged;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.config.RuneLiteConfig;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.OverlayMenuClicked;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseManager;
import net.runelite.client.ui.ClientUI;
import net.runelite.client.ui.JagexColors;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayBounds;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayMenuEntry;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;
import net.runelite.client.ui.overlay.tooltip.Tooltip;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;
import net.runelite.client.util.ColorUtil;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

@Singleton
@Slf4j
public class MinimalOverlayRenderer extends MouseAdapter implements KeyListener
{
	private static final Marker DEDUPLICATE = MarkerFactory.getMarker("DEDUPLICATE");
	private static final int BORDER = 5;
	private static final int BORDER_TOP = BORDER + 15;
	private static final int PADDING = 2;
	private static final int OVERLAY_RESIZE_TOLERANCE = 5;
	private static final Dimension SNAP_CORNER_SIZE = new Dimension(80, 80);
	private static final Color SNAP_CORNER_COLOR = new Color(0, 255, 255, 50);
	private static final Color SNAP_CORNER_ACTIVE_COLOR = new Color(0, 255, 0, 100);
	private static final Color MOVING_OVERLAY_COLOR = new Color(255, 255, 0, 100);
	private static final Color MOVING_OVERLAY_ACTIVE_COLOR = new Color(255, 255, 0, 200);
	private static final Color MOVING_OVERLAY_TARGET_COLOR = Color.RED;
	private static final Color MOVING_OVERLAY_RESIZING_COLOR = new Color(255, 0, 255, 200);
	private final Client client;
	private final OverlayManager overlayManager;
	private final TooltipManager tooltipManager;
	private final RuneLiteConfig runeLiteConfig;
	private final EventBus eventBus;

	// Overlay movement variables
	private final Point overlayOffset = new Point();
	private final Point mousePosition = new Point();
	private Overlay currentManagedOverlay;
	private Overlay dragTargetOverlay;
	private Rectangle currentManagedBounds;
	private boolean inOverlayManagingMode;
	private boolean inOverlayResizingMode;
	private boolean inOverlayDraggingMode;
	private boolean startedMovingOverlay;
	private Overlay hoveredOverlay; // for building menu entries

	// Overlay state validation
	private Rectangle viewportBounds;
	private Rectangle chatboxBounds;
	private boolean chatboxHidden;
	private boolean isResizeable;
	private OverlayBounds emptySnapCorners, snapCorners;

	// focused overlay
	private Overlay focusedOverlay;
	private Overlay prevFocusedOverlay;

	@Inject
	private MinimalOverlayRenderer(
		final Client client,
		final OverlayManager overlayManager,
		final RuneLiteConfig runeLiteConfig,
		final MouseManager mouseManager,
		final KeyManager keyManager,
		final TooltipManager tooltipManager,
		final ClientUI clientUI,
		final EventBus eventBus)
	{
		this.client = client;
		this.overlayManager = overlayManager;
		this.runeLiteConfig = runeLiteConfig;
		this.tooltipManager = tooltipManager;
		this.eventBus = eventBus;
		keyManager.registerKeyListener(this);
		mouseManager.registerMouseListener(this);
		eventBus.register(this);
	}

	@Subscribe
	public void onFocusChanged(FocusChanged event)
	{
		if (!event.isFocused())
		{
			if (inOverlayManagingMode)
			{
				inOverlayManagingMode = false;
				resetOverlayManagementMode();
			}

			hoveredOverlay = null;
		}
	}

	@Subscribe
	protected void onClientTick(ClientTick t)
	{
		final Overlay overlay = hoveredOverlay;
		if (overlay == null || client.isMenuOpen())
		{
			return;
		}

		final boolean shift = client.isKeyPressed(KeyCode.KC_SHIFT);
		if (!shift && runeLiteConfig.menuEntryShift())
		{
			return;
		}

		List<OverlayMenuEntry> menuEntries = overlay.getMenuEntries();
		if (menuEntries.isEmpty())
		{
			return;
		}

		// Add in reverse order so they display correctly in the right-click menu
		for (int i = menuEntries.size() - 1; i >= 0; --i)
		{
			OverlayMenuEntry overlayMenuEntry = menuEntries.get(i);

			client.createMenuEntry(-1)
				.setOption(overlayMenuEntry.getOption())
				.setTarget(ColorUtil.wrapWithColorTag(overlayMenuEntry.getTarget(), JagexColors.MENU_TARGET))
				.setType(overlayMenuEntry.getMenuAction())
				.onClick(e -> eventBus.post(new OverlayMenuClicked(overlayMenuEntry, overlay)));
		}
	}

	@Subscribe
	public void onBeforeRender(BeforeRender event)
	{
		hoveredOverlay = null;

		if (focusedOverlay == null && prevFocusedOverlay != null)
		{
			prevFocusedOverlay.onMouseExit();
		}

		prevFocusedOverlay = focusedOverlay;
		focusedOverlay = null;

		if (client.getGameState() == GameState.LOGGED_IN)
		{

			if (shouldInvalidateBounds())
			{
				emptySnapCorners = buildSnapCorners();
			}

			// Create copy of snap corners because overlays will modify them
			snapCorners = new OverlayBounds(emptySnapCorners);
		}
	}

	public void renderOverlayLayer(Graphics2D graphics, final OverlayLayer layer)
	{
		final Collection<Overlay> overlays = overlayManager.getLayer(layer);
		renderOverlays(graphics, overlays, layer);
	}

	public void renderAfterInterface(Graphics2D graphics, int interfaceId, Collection<WidgetItem> widgetItems)
	{
		Collection<Overlay> overlays = overlayManager.getForInterface(interfaceId);
		overlayManager.setWidgetItems(widgetItems);
		renderOverlays(graphics, overlays, OverlayLayer.ABOVE_WIDGETS);
		overlayManager.setWidgetItems(Collections.emptyList());
	}

	public void renderAfterLayer(Graphics2D graphics, Widget layer, Collection<WidgetItem> widgetItems)
	{
		Collection<Overlay> overlays = overlayManager.getForLayer(layer.getId());
		overlayManager.setWidgetItems(widgetItems);
		renderOverlays(graphics, overlays, OverlayLayer.ABOVE_WIDGETS);
		overlayManager.setWidgetItems(Collections.emptyList());
	}

	private void renderOverlays(Graphics2D graphics, Collection<Overlay> overlays, OverlayLayer layer)
	{
		if (overlays == null
			|| overlays.isEmpty()
			|| client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		OverlayUtil.setGraphicProperties(graphics);

		// Draw snap corners
		if (inOverlayDraggingMode && layer == OverlayLayer.UNDER_WIDGETS && currentManagedOverlay != null && currentManagedOverlay.getPosition() != OverlayPosition.DETACHED)
		{
			final OverlayBounds translatedSnapCorners = snapCorners.translated(
				-SNAP_CORNER_SIZE.width,
				-SNAP_CORNER_SIZE.height);

			final Color previous = graphics.getColor();

			for (final Rectangle corner : translatedSnapCorners.getBounds())
			{
				graphics.setColor(corner.contains(mousePosition) ? SNAP_CORNER_ACTIVE_COLOR : SNAP_CORNER_COLOR);
				graphics.fill(corner);
			}

			graphics.setColor(previous);
		}

		// Get mouse position
		final net.runelite.api.Point mouseCanvasPosition = client.getMouseCanvasPosition();
		final Point mouse = new Point(mouseCanvasPosition.getX(), mouseCanvasPosition.getY());

		// Save graphics2d properties so we can restore them later
		final AffineTransform transform = graphics.getTransform();
		final Stroke stroke = graphics.getStroke();
		final Composite composite = graphics.getComposite();
		final Paint paint = graphics.getPaint();
		final RenderingHints renderingHints = graphics.getRenderingHints();
		final Color background = graphics.getBackground();

		for (Overlay overlay : overlays)
		{
			final OverlayPosition overlayPosition = getCorrectedOverlayPosition(overlay);

			if (overlayPosition == OverlayPosition.DYNAMIC || overlayPosition == OverlayPosition.TOOLTIP)
			{
				safeRender(client, overlay, layer, graphics, new Point());

				// Restore graphics2d properties
				graphics.setTransform(transform);
				graphics.setStroke(stroke);
				graphics.setComposite(composite);
				graphics.setPaint(paint);
				graphics.setRenderingHints(renderingHints);
				graphics.setBackground(background);
			}
			else
			{
				final Rectangle bounds = overlay.getBounds();
				final Dimension dimension = bounds.getSize();
				final Point preferredLocation = overlay.getPreferredLocation();
				Point location;
				Rectangle snapCorner = null;

				// If the final position is not modified, layout it
				if (overlayPosition != OverlayPosition.DETACHED && (preferredLocation == null || overlay.getPreferredPosition() != null))
				{
					snapCorner = snapCorners.forPosition(overlayPosition);
					final Point translation = OverlayUtil.transformPosition(overlayPosition, dimension); // offset from corner
					// Target x/y to draw the overlay
					int destX = snapCorner.x + translation.x;
					int destY = snapCorner.y + translation.y;
					// Clamp the target position to ensure it is on screen or within parent bounds
					location = clampOverlayLocation(destX, destY, dimension.width, dimension.height, overlay);
				}
				else
				{
					location = preferredLocation != null ? preferredLocation : bounds.getLocation();

					// Clamp the overlay position to ensure it is on screen or within parent bounds
					location = clampOverlayLocation(location.x, location.y, dimension.width, dimension.height, overlay);
				}

				if (overlay.getPreferredSize() != null)
				{
					bounds.setSize(overlay.getPreferredSize());
				}

				safeRender(client, overlay, layer, graphics, location);

				// Adjust snap corner based on where the overlay was drawn
				if (snapCorner != null && bounds.width + bounds.height > 0)
				{
					OverlayUtil.shiftSnapCorner(overlayPosition, snapCorner, bounds, PADDING);
				}

				// Restore graphics2d properties prior to drawing bounds
				graphics.setTransform(transform);
				graphics.setStroke(stroke);
				graphics.setComposite(composite);
				graphics.setPaint(paint);
				graphics.setRenderingHints(renderingHints);
				graphics.setBackground(background);

				if (!bounds.isEmpty())
				{
					if (inOverlayManagingMode)
					{
						Color boundsColor;
						if (inOverlayResizingMode && currentManagedOverlay == overlay)
						{
							boundsColor = MOVING_OVERLAY_RESIZING_COLOR;
						}
						else if (inOverlayDraggingMode && currentManagedOverlay == overlay)
						{
							boundsColor = MOVING_OVERLAY_ACTIVE_COLOR;
						}
						else if (inOverlayDraggingMode && overlay.isDragTargetable() && currentManagedOverlay.isDragTargetable()
							&& currentManagedOverlay.getBounds().intersects(bounds))
						{
							boundsColor = MOVING_OVERLAY_TARGET_COLOR;
							assert currentManagedOverlay != overlay;
							dragTargetOverlay = overlay;
						}
						else
						{
							boundsColor = MOVING_OVERLAY_COLOR;
						}

						graphics.setColor(boundsColor);
						graphics.draw(bounds);
						graphics.setPaint(paint);
					}

					if (!client.isMenuOpen() && !client.getSpellSelected() && bounds.contains(mouse))
					{
						hoveredOverlay = overlay;

						if (inOverlayManagingMode)
						{
							String tooltipText = overlay.getPlugin() == null ? overlay.getName() : overlay.getPlugin().getName();
							tooltipManager.add(new Tooltip(tooltipText));
						}

						if (focusedOverlay == null)
						{
							focusedOverlay = overlay;
							if (focusedOverlay != prevFocusedOverlay)
							{
								if (prevFocusedOverlay != null)
								{
									prevFocusedOverlay.onMouseExit();
								}

								overlay.onMouseEnter();
							}
						}

						overlay.onMouseOver();
					}
				}
			}
		}
	}

	@Override
	public MouseEvent mousePressed(MouseEvent mouseEvent)
	{
		if (!inOverlayManagingMode)
		{
			return mouseEvent;
		}

		final Point mousePoint = mouseEvent.getPoint();
		mousePosition.setLocation(mousePoint);

		// See if we've clicked on an overlay
		currentManagedOverlay = findMangedOverlay(mousePoint);

		if (currentManagedOverlay == null)
		{
			return mouseEvent;
		}

		if (SwingUtilities.isRightMouseButton(mouseEvent))
		{
			if (currentManagedOverlay.isResettable())
			{
				overlayManager.resetOverlay(currentManagedOverlay);
			}
		}
		else if (SwingUtilities.isLeftMouseButton(mouseEvent))
		{
			final Point offset = new Point(mousePoint.x, mousePoint.y);
			offset.translate(-currentManagedOverlay.getBounds().x, -currentManagedOverlay.getBounds().y);
			overlayOffset.setLocation(offset);

			inOverlayResizingMode = currentManagedOverlay != null && currentManagedOverlay.isResizable();
			inOverlayDraggingMode = !inOverlayResizingMode;
			startedMovingOverlay = true;
			currentManagedBounds = new Rectangle(currentManagedOverlay.getBounds());
		}
		else
		{
			return mouseEvent;
		}

		mouseEvent.consume();
		return mouseEvent;
	}

	@Override
	public MouseEvent mouseMoved(MouseEvent mouseEvent)
	{
		if (!inOverlayManagingMode)
		{
			return mouseEvent;
		}

		final Point mousePoint = mouseEvent.getPoint();
		mousePosition.setLocation(mousePoint);

		if (!inOverlayResizingMode && !inOverlayDraggingMode)
		{
			currentManagedOverlay = findMangedOverlay(mousePoint);
		}

		if (currentManagedOverlay == null || !currentManagedOverlay.isResizable())
		{
			return mouseEvent;
		}

		final Rectangle toleranceRect = new Rectangle(currentManagedOverlay.getBounds());
		toleranceRect.grow(-OVERLAY_RESIZE_TOLERANCE, -OVERLAY_RESIZE_TOLERANCE);

		return mouseEvent;
	}

	/**
	 * Find an overlay to manage which is under the given mouse point
	 *
	 * @param mousePoint
	 * @return
	 */
	private Overlay findMangedOverlay(Point mousePoint)
	{
		synchronized (overlayManager)
		{
			// render order is roughly: under -> manual -> above -> always on top
			final List<OverlayLayer> layerOrder = ImmutableList.of(OverlayLayer.UNDER_WIDGETS, OverlayLayer.MANUAL, OverlayLayer.ABOVE_WIDGETS, OverlayLayer.ALWAYS_ON_TOP);
			return overlayManager.getOverlays()
					.stream()
					// ABOVE_SCENE overlays aren't managed
					.filter(c -> layerOrder.contains(c.getLayer()))
					// never allow moving dynamic or tooltip overlays
					.filter(c -> c.getPosition() != OverlayPosition.DYNAMIC && c.getPosition() != OverlayPosition.TOOLTIP)
					.sorted(
							Comparator.<Overlay>comparingInt(c -> layerOrder.indexOf(c.getLayer()))
									.thenComparing(OverlayManager.OVERLAY_COMPARATOR)
									// pick order is reversed from render order
									.reversed()
					)
					.filter(o -> o.getBounds().contains(mousePoint))
					.findFirst()
					.orElse(null);
		}
	}

	@Override
	public MouseEvent mouseDragged(MouseEvent mouseEvent)
	{
		if (!inOverlayManagingMode)
		{
			return mouseEvent;
		}

		final Point p = mouseEvent.getPoint();
		mousePosition.setLocation(p);

		if (currentManagedOverlay == null)
		{
			return mouseEvent;
		}

		if (dragTargetOverlay != null && !currentManagedOverlay.getBounds().intersects(dragTargetOverlay.getBounds()))
		{
			// No longer over drag target
			dragTargetOverlay = null;
		}

		final Rectangle canvasRect = new Rectangle(client.getRealDimensions());

		if (!canvasRect.contains(p))
		{
			return mouseEvent;
		}

		if (inOverlayResizingMode)
		{
			final int originalX = currentManagedBounds.x;
			final int originalY = currentManagedBounds.y;
			int x = originalX;
			int y = originalY;
			int width = currentManagedBounds.width;
			int height = currentManagedBounds.height;

			final int minOverlaySize = currentManagedOverlay.getMinimumSize();
			final int widthOverflow = Math.max(0, minOverlaySize - width);
			final int heightOverflow = Math.max(0, minOverlaySize - height);
			final int dx = x - originalX;
			final int dy = y - originalY;

			// If this resize operation would cause the dimensions to go below the minimum width/height, reset the
			// dimensions and adjust the x/y position accordingly as needed
			if (widthOverflow > 0)
			{
				width = minOverlaySize;

				if (dx > 0)
				{
					x -= widthOverflow;
				}
			}
			if (heightOverflow > 0)
			{
				height = minOverlaySize;

				if (dy > 0)
				{
					y -= heightOverflow;
				}
			}

			currentManagedBounds.setRect(x, y, width, height);
			currentManagedOverlay.setPreferredSize(new Dimension(currentManagedBounds.width, currentManagedBounds.height));

			if (currentManagedOverlay.getPreferredLocation() != null)
			{
				currentManagedOverlay.setPreferredLocation(currentManagedBounds.getLocation());
			}
		}
		else if (inOverlayDraggingMode)
		{
			Point overlayPosition = new Point(p);
			overlayPosition.translate(-overlayOffset.x, -overlayOffset.y); // adjust by mouse offset to get overlay position

			// Clamp drag to parent component
			final Rectangle overlayBounds = currentManagedOverlay.getBounds();
			overlayPosition = clampOverlayLocation(overlayPosition.x, overlayPosition.y, overlayBounds.width, overlayBounds.height, currentManagedOverlay);
			currentManagedOverlay.setPreferredPosition(null);
			currentManagedOverlay.setPreferredLocation(overlayPosition);
		}
		else
		{
			return mouseEvent;
		}

		if (startedMovingOverlay)
		{
			// Move currently moved overlay to correct layer
			overlayManager.rebuildOverlayLayers();
			startedMovingOverlay = false;
		}

		mouseEvent.consume();
		return mouseEvent;
	}

	@Override
	public MouseEvent mouseReleased(MouseEvent mouseEvent)
	{
		if (!inOverlayManagingMode || currentManagedOverlay == null || (!inOverlayDraggingMode && !inOverlayResizingMode))
		{
			return mouseEvent;
		}

		mousePosition.setLocation(-1, -1);

		if (dragTargetOverlay != null)
		{
			if (dragTargetOverlay.onDrag(currentManagedOverlay))
			{
				mouseEvent.consume();
				resetOverlayManagementMode();
				return mouseEvent;
			}
		}

		// Check if the overlay is over a snapcorner and move it if so, unless it is a detached overlay
		if (currentManagedOverlay.getPosition() != OverlayPosition.DETACHED && inOverlayDraggingMode)
		{
			final OverlayBounds snapCorners = this.emptySnapCorners.translated(-SNAP_CORNER_SIZE.width, -SNAP_CORNER_SIZE.height);

			for (Rectangle snapCorner : snapCorners.getBounds())
			{
				if (snapCorner.contains(mouseEvent.getPoint()))
				{
					OverlayPosition position = snapCorners.fromBounds(snapCorner);

					if (position == getCorrectedOverlayPosition(currentManagedOverlay))
					{
						// overlay moves back to default position
						position = null;
					}

					currentManagedOverlay.setPreferredPosition(position);
					currentManagedOverlay.setPreferredLocation(null); // from dragging
					currentManagedOverlay.revalidate();
					break;
				}
			}
		}

		overlayManager.saveOverlay(currentManagedOverlay);
		resetOverlayManagementMode();
		mouseEvent.consume();
		return mouseEvent;
	}

	@Override
	public void keyTyped(KeyEvent e)
	{
	}

	@Override
	public void keyPressed(KeyEvent e)
	{
		if (e.isAltDown())
		{
			inOverlayManagingMode = true;
		}
	}

	@Override
	public void keyReleased(KeyEvent e)
	{
		if (!e.isAltDown() && inOverlayManagingMode)
		{
			inOverlayManagingMode = false;
			resetOverlayManagementMode();
		}
	}

	private void safeRender(Client client, Overlay overlay, OverlayLayer layer, Graphics2D graphics, Point point)
	{
		if (!isResizeable && (layer == OverlayLayer.ABOVE_SCENE || layer == OverlayLayer.UNDER_WIDGETS))
		{
			graphics.setClip(client.getViewportXOffset(),
				client.getViewportYOffset(),
				client.getViewportWidth(),
				client.getViewportHeight());
		}
		else
		{
			graphics.setClip(0, 0, client.getCanvasWidth(), client.getCanvasHeight());
		}

		final OverlayPosition position = overlay.getPosition();

		// Set font based on configuration
		if (position == OverlayPosition.DYNAMIC || position == OverlayPosition.DETACHED)
		{
			graphics.setFont(runeLiteConfig.fontType().getFont());
		}
		else if (position == OverlayPosition.TOOLTIP)
		{
			graphics.setFont(runeLiteConfig.tooltipFontType().getFont());
		}
		else
		{
			graphics.setFont(runeLiteConfig.interfaceFontType().getFont());
		}

		graphics.translate(point.x, point.y);
		overlay.getBounds().setLocation(point);

		final Dimension overlayDimension;
		try
		{
			overlayDimension = overlay.render(graphics);
		}
		catch (Exception ex)
		{
			log.warn(DEDUPLICATE, "Error during overlay rendering", ex);
			return;
		}

		final Dimension dimension = MoreObjects.firstNonNull(overlayDimension, new Dimension());
		overlay.getBounds().setSize(dimension);
	}

	private OverlayPosition getCorrectedOverlayPosition(final Overlay overlay)
	{
		OverlayPosition overlayPosition = overlay.getPosition();

		if (overlay.getPreferredPosition() != null)
		{
			overlayPosition = overlay.getPreferredPosition();
		}

		if (!isResizeable)
		{
			// On fixed mode, ABOVE_CHATBOX_RIGHT is in the same location as
			// BOTTOM_RIGHT and CANVAS_TOP_RIGHT is same as TOP_RIGHT.
			// Just use BOTTOM_RIGHT and TOP_RIGHT to prevent overlays from
			// drawing over each other.
			switch (overlayPosition)
			{
				case CANVAS_TOP_RIGHT:
					overlayPosition = OverlayPosition.TOP_RIGHT;
					break;
				case ABOVE_CHATBOX_RIGHT:
					overlayPosition = OverlayPosition.BOTTOM_RIGHT;
					break;
			}
		}

		return overlayPosition;
	}

	private void resetOverlayManagementMode()
	{
		inOverlayResizingMode = false;
		inOverlayDraggingMode = false;
		currentManagedOverlay = null;
		dragTargetOverlay = null;
		currentManagedBounds = null;
	}

	private boolean shouldInvalidateBounds()
	{
		final Widget chatbox = client.getWidget(WidgetInfo.CHATBOX);
		final boolean resizeableChanged = isResizeable != client.isResized();
		boolean changed = false;

		if (resizeableChanged)
		{
			isResizeable = client.isResized();
			changed = true;
		}

		final boolean chatboxBoundsChanged = chatbox == null || !chatbox.getBounds().equals(chatboxBounds);

		if (chatboxBoundsChanged)
		{
			chatboxBounds = chatbox != null ? chatbox.getBounds() : new Rectangle();
			changed = true;
		}

		final boolean chatboxHiddenChanged = chatboxHidden != (chatbox == null || chatbox.isHidden());

		if (chatboxHiddenChanged)
		{
			chatboxHidden = chatbox == null || chatbox.isHidden();
			changed = true;
		}

		Widget viewportWidget = getViewportLayer();
		Rectangle viewport = viewportWidget != null ? viewportWidget.getBounds() : new Rectangle();
		final boolean viewportChanged = !viewport.equals(viewportBounds);

		if (viewportChanged)
		{
			viewportBounds = viewport;
			changed = true;
		}

		return changed;
	}

	private Widget getViewportLayer()
	{
		if (client.isResized())
		{
			if (client.getVar(Varbits.SIDE_PANELS) == 1)
			{
				return client.getWidget(WidgetInfo.RESIZABLE_VIEWPORT_BOTTOM_LINE);
			}
			else
			{
				return client.getWidget(WidgetInfo.RESIZABLE_VIEWPORT_OLD_SCHOOL_BOX);
			}
		}
		return client.getWidget(WidgetInfo.FIXED_VIEWPORT);
	}

	private OverlayBounds buildSnapCorners()
	{
		final Point topLeftPoint = new Point(
			viewportBounds.x + BORDER,
			viewportBounds.y + BORDER_TOP);

		final Point topCenterPoint = new Point(
			viewportBounds.x + viewportBounds.width / 2,
			viewportBounds.y + BORDER
		);

		final Point topRightPoint = new Point(
			viewportBounds.x + viewportBounds.width - BORDER,
			topCenterPoint.y);

		final Point bottomLeftPoint = new Point(
			topLeftPoint.x,
			viewportBounds.y + viewportBounds.height - BORDER);

		final Point bottomRightPoint = new Point(
			topRightPoint.x,
			bottomLeftPoint.y);

		// Check to see if chat box is minimized
		if (isResizeable && chatboxHidden)
		{
			bottomLeftPoint.y += chatboxBounds.height;
		}

		final Point rightChatboxPoint = isResizeable ? new Point(
			viewportBounds.x + chatboxBounds.width - BORDER,
			bottomLeftPoint.y) : bottomRightPoint;

		final Point canvasTopRightPoint = isResizeable ? new Point(
			(int) client.getRealDimensions().getWidth(),
			0) : topRightPoint;

		return new OverlayBounds(
			new Rectangle(topLeftPoint, SNAP_CORNER_SIZE),
			new Rectangle(topCenterPoint, SNAP_CORNER_SIZE),
			new Rectangle(topRightPoint, SNAP_CORNER_SIZE),
			new Rectangle(bottomLeftPoint, SNAP_CORNER_SIZE),
			new Rectangle(bottomRightPoint, SNAP_CORNER_SIZE),
			new Rectangle(rightChatboxPoint, SNAP_CORNER_SIZE),
			new Rectangle(canvasTopRightPoint, SNAP_CORNER_SIZE));
	}

	/**
	 * Adjust the given overlay position to be within its parent's bounds.
	 *
	 * @param overlayX
	 * @param overlayY
	 * @param overlayWidth
	 * @param overlayHeight
	 * @param overlay       the overlay
	 * @return the clamped position
	 */
	private Point clampOverlayLocation(int overlayX, int overlayY, int overlayWidth, int overlayHeight, Overlay overlay)
	{
		Rectangle parentBounds = overlay.getParentBounds();
		if (parentBounds == null || parentBounds.isEmpty())
		{
			// If no bounds are set, use the full client bounds
			Dimension dim = client.getRealDimensions();
			parentBounds = new Rectangle(0, 0, dim.width, dim.height);
		}

		// Constrain overlay position to be within the parent bounds
		return new Point(
			Ints.constrainToRange(overlayX, parentBounds.x,
				Math.max(parentBounds.x, parentBounds.x + parentBounds.width - overlayWidth)),
			Ints.constrainToRange(overlayY, parentBounds.y,
				Math.max(parentBounds.y, parentBounds.y + parentBounds.height - overlayHeight))
		);
	}
}
