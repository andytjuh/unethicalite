package net.unethicalite.mixins;

import net.runelite.api.ItemComposition;
import net.runelite.api.Skill;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.events.StatChanged;
import net.runelite.api.mixins.Copy;
import net.runelite.api.mixins.FieldHook;
import net.runelite.api.mixins.Inject;
import net.runelite.api.mixins.MethodHook;
import net.runelite.api.mixins.Mixin;
import net.runelite.api.mixins.Replace;
import net.runelite.api.mixins.Shadow;
import net.runelite.api.widgets.Widget;
import net.runelite.rs.api.RSActor;
import net.runelite.rs.api.RSClient;
import net.runelite.rs.api.RSGameObject;
import net.runelite.rs.api.RSGraphicsObject;
import net.runelite.rs.api.RSItemComposition;
import net.runelite.rs.api.RSProjectile;
import net.runelite.rs.api.RSRenderable;
import net.runelite.rs.api.RSRuneLiteMenuEntry;
import net.runelite.rs.api.RSTile;
import net.unethicalite.api.events.ExperienceGained;
import net.unethicalite.api.events.LobbyWorldSelectToggled;
import net.unethicalite.api.events.LoginStateChanged;
import net.unethicalite.api.events.MenuAutomated;
import net.unethicalite.api.events.PlaneChanged;
import net.unethicalite.api.events.WorldHopped;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicReference;

@Mixin(RSClient.class)
public abstract class HClientMixin implements RSClient
{
	@Inject
	private static final int[] previousExp = new int[23];
	@Inject
	private static final AtomicReference<MenuAutomated> automatedMenu = new AtomicReference<>(null);
	@Inject
	public static HashMap<Integer, RSItemComposition> itemDefCache = new HashMap<>();
	@Shadow("client")
	private static RSClient client;
	@Shadow("rl$menuEntries")
	private static RSRuneLiteMenuEntry[] rl$menuEntries;
	@Shadow("printMenuActions")
	private static boolean printMenuActions;
	@Inject
	private static boolean lowCpu;
	@Inject
	private static volatile MenuAutomated queuedMenu;

	@Copy("drawWidgets")
	@Replace("drawWidgets")
	static final void copy$drawWidgets(int var0, int var1, int var2, int var3, int var4, int var5, int var6, int var7)
	{
		if (!lowCpu)
		{
			copy$drawWidgets(var0, var1, var2, var3, var4, var5, var6, var7);
		}
	}

	@Copy("drawModelComponents")
	@Replace("drawModelComponents")
	static void copy$drawModelComponents(Widget[] var0, int var1)
	{
		if (!lowCpu)
		{
			copy$drawModelComponents(var0, var1);
		}
	}

	@Inject
	@FieldHook("loginIndex")
	public static void loginIndex(int idx)
	{
		client.getCallbacks().post(new LoginStateChanged(client.getLoginIndex()));
	}

	@FieldHook("experience")
	@Inject
	public static void experiencedChanged(int idx)
	{
		Skill[] possibleSkills = Skill.values();

		// We subtract one here because 'Overall' isn't considered a skill that's updated.
		if (idx < possibleSkills.length - 1)
		{
			Skill updatedSkill = possibleSkills[idx];
			StatChanged statChanged = new StatChanged(
					updatedSkill,
					client.getSkillExperience(updatedSkill),
					client.getRealSkillLevel(updatedSkill),
					client.getBoostedSkillLevel(updatedSkill)
			);
			if (previousExp[idx] == 0 && client.getSkillExperience(updatedSkill) > 0)
			{
				previousExp[idx] = client.getSkillExperience(updatedSkill);
			}

			experienceGained(idx, client.getSkillExperience(updatedSkill), client.getRealSkillLevel(updatedSkill), updatedSkill);
			client.getCallbacks().post(statChanged);
		}
	}

	@Inject
	public static void experienceGained(int idx, int exp, int skillLevel, Skill updatedSkill)
	{
		if (exp > previousExp[idx])
		{
			int gained = exp - previousExp[idx];

			ExperienceGained experienceGained = new ExperienceGained(
					updatedSkill,
					gained,
					exp,
					skillLevel
			);

			client.getCallbacks().post(experienceGained);
			previousExp[idx] = exp;
		}
	}

	@Inject
	@FieldHook("Client_plane")
	public static void clientPlaneChanged(int idx)
	{
		client.getCallbacks().post(new PlaneChanged(client.getPlane()));
	}

	@Inject
	@MethodHook("menu")
	public void menu()
	{
		MenuAutomated menu = automatedMenu.getAndSet(null);
		if (menu != null)
		{
			client.setDraggedWidget(null);
			client.setIf1DraggedWidget(null);
			client.setMenuOptionCount(1);
			int idx = client.getMenuOptionCount() - 1;

			client.getMenuArguments1()[idx] = menu.getParam0();
			client.getMenuArguments2()[idx] = menu.getParam1();
			client.getMenuOpcodes()[idx] = menu.getOpcode().getId();
			client.getMenuIdentifiers()[idx] = menu.getIdentifier();
			client.getMenuOptions()[idx] = menu.getOption();
			client.getMenuTargets()[idx] = menu.getTarget();
			client.getMenuForceLeftClick()[idx] = true;
		}
	}

	@Inject
	@Override
	@Nonnull
	public ItemComposition getItemComposition(int id)
	{
		if (itemDefCache.containsKey(id))
		{
			return itemDefCache.get(id);
		}

		assert this.isClientThread() : "getItemComposition must be called on client thread";
		RSItemComposition def = getRSItemDefinition(id);
		itemDefCache.put(id, def);
		return def;
	}

	@Inject
	public void interact(MenuAutomated menuAutomated)
	{
		client.getCallbacks().post(menuAutomated);
	}

	@Inject
	@Override
	public String getLoginMessage()
	{
		if (getLoginIndex() == 14)
		{
			if (getBanType() == 0)
			{
				return "Your account has been disabled. Please visit the support page for assistance.";
			}

			if (getBanType() == 1)
			{
				return "Account locked as we suspect it has been stolen. Please visit the support page for assistance.";
			}
		}

		if (getLoginIndex() == 3)
		{
			return "Invalid credentials.";
		}

		return getLoginResponse1() + " " + getLoginResponse2() + " " + getLoginResponse3();
	}

	@Override
	@Inject
	public boolean isTileObjectValid(Tile tile, TileObject t)
	{
		if (!(t instanceof RSGameObject))
		{
			return true;
		}

		// actors, projectiles, and graphics objects are added and removed from the scene each frame as GameObjects,
		// so ignore them.
		RSGameObject gameObject = (RSGameObject) t;
		RSRenderable renderable = gameObject.getRenderable();
		boolean invalid = renderable instanceof RSActor || renderable instanceof RSProjectile || renderable instanceof RSGraphicsObject;
		invalid |= gameObject.getStartX() != ((RSTile) tile).getX() || gameObject.getStartY() != ((RSTile) tile).getY();
		return !invalid;
	}

	@Inject
	@Override
	public boolean isItemDefinitionCached(int id)
	{
		return itemDefCache.containsKey(id);
	}

	@Inject
	@Override
	public boolean isLowCpu()
	{
		return lowCpu;
	}

	@Inject
	@Override
	public void setLowCpu(boolean enabled)
	{
		lowCpu = enabled;
	}

	@Inject
	@Override
	public void uncacheItem(int id)
	{
		itemDefCache.remove(id);
	}

	@Inject
	@Override
	public void cacheItem(int id, ItemComposition item)
	{
		itemDefCache.put(id, (RSItemComposition) item);
	}

	@Inject
	@Override
	public void clearItemCache()
	{
		itemDefCache.clear();
	}

	@Inject
	@Override
	public void setPendingAutomation(MenuAutomated replacement)
	{
		automatedMenu.set(replacement);
	}

	@Inject
	public void setQueuedMenu(MenuAutomated menuAutomated)
	{
		queuedMenu = menuAutomated;
	}

	@Inject
	public MenuAutomated getQueuedMenu()
	{
		return queuedMenu;
	}

	@Inject
	@FieldHook("worldId")
	public static void onWorldHopped(int idx)
	{
		if (client != null)
		{
			client.getCallbacks().post(new WorldHopped(client.getWorld()));
		}
	}

	@FieldHook("worldSelectOpen")
	@Inject
	public static void worldSelectionScreenToggled(int idx)
	{
		if (!client.isWorldSelectOpen())
		{
			Arrays.fill(client.getBufferProvider().getPixels(), 0);
		}

		client.getCallbacks().post(new LobbyWorldSelectToggled(client.isWorldSelectOpen()));
	}
}
