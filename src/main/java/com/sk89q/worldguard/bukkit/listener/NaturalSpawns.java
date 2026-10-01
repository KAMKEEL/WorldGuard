/*
 * WorldGuard, a suite of tools for Minecraft
 * Copyright (C) sk89q <http://www.sk89q.com>
 * Copyright (C) WorldGuard team and contributors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package com.sk89q.worldguard.bukkit.listener;

import com.sk89q.worldguard.bukkit.ConfigurationManager;
import com.sk89q.worldguard.bukkit.WorldConfiguration;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.flags.DefaultFlag;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Ghast;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Slime;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.logging.Level;

/**
 * The mob-spawning, monster-spawning and animal-spawning flags. They stop natural spawns only.
 * On Crucible the spawner asks here before it builds the mob, so a refused spawn costs almost nothing.
 */
public final class NaturalSpawns {

    private static final String GATE = "io.github.crucible.NaturalSpawnGate";
    private static final String IMOB = "net.minecraft.entity.monster.IMob";

    private static final Map<Class<?>, Method> handles = new HashMap<Class<?>, Method>();
    private static Class<?> imob;
    private static boolean lookedUp;

    private NaturalSpawns() {
    }

    static boolean isNatural(SpawnReason reason) {
        return reason == SpawnReason.NATURAL || reason == SpawnReason.CHUNK_GEN;
    }

    /** Whether the flags in this set stop a natural spawn of this kind of mob. */
    static boolean denied(ApplicableRegionSet set, boolean monster) {
        if (!set.allows(DefaultFlag.MOB_SPAWNING)) {
            return true;
        }
        return !set.allows(monster ? DefaultFlag.MONSTER_SPAWNING : DefaultFlag.ANIMAL_SPAWNING);
    }

    /** A modded monster has no Bukkit type, so this falls back to the game's own monster marker. */
    static boolean isMonster(Entity entity) {
        if (entity instanceof Monster || entity instanceof Slime || entity instanceof Ghast) {
            return true;
        }
        try {
            if (!lookedUp) {
                lookedUp = true;
                imob = Class.forName(IMOB, false, entity.getClass().getClassLoader());
            }
            if (imob == null) {
                return false;
            }
            Method getHandle = handles.get(entity.getClass());
            if (getHandle == null) {
                getHandle = entity.getClass().getMethod("getHandle");
                handles.put(entity.getClass(), getHandle);
            }
            return imob.isInstance(getHandle.invoke(entity));
        } catch (Exception e) {
            return false;
        }
    }

    /** Hands Crucible the check it makes before building a naturally spawned mob. Does nothing on other servers. */
    public static void registerGate(final WorldGuardPlugin plugin) {
        BiPredicate<Location, Boolean> gate = (location, monster) -> {
            ConfigurationManager cfg = plugin.getGlobalStateManager();
            if (cfg.activityHaltToggle) {
                return false;
            }
            WorldConfiguration wcfg = cfg.get(location.getWorld());
            if (!wcfg.useRegions || !cfg.useRegionsCreatureSpawnEvent) {
                return true;
            }
            ApplicableRegionSet set = plugin.getRegionContainer().createQuery().getApplicableRegions(location);
            return !denied(set, monster);
        };
        setGate(plugin, gate);
    }

    public static void unregisterGate(WorldGuardPlugin plugin) {
        setGate(plugin, null);
    }

    private static void setGate(WorldGuardPlugin plugin, BiPredicate<Location, Boolean> gate) {
        try {
            Class.forName(GATE).getMethod("set", BiPredicate.class).invoke(null, gate);
            if (gate != null) {
                plugin.getLogger().info("Natural spawns are checked before the mob is built.");
            }
        } catch (ClassNotFoundException e) {
            // Not Crucible. The spawn event still applies the flags.
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Could not hand the spawn check to Crucible", e);
        }
    }
}
