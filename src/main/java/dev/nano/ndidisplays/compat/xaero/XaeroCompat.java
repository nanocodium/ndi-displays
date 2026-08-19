package dev.nano.ndidisplays.compat.xaero;

import dev.nano.ndidisplays.entity.DroneEntity;
import dev.nano.ndidisplays.net.DroneImportWaypointsPacket;
import dev.nano.ndidisplays.net.NetworkHandler;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Optional Xaero World Map / Minimap bridge. No hard dependency: every call is
 * reflection, and a missing or relocated API just returns an empty list.
 */
public final class XaeroCompat {

    public static final boolean LOADED = ModList.get().isLoaded("xaerominimap")
            || ModList.get().isLoaded("xaeroworldmap");

    private XaeroCompat() {
    }

    public static boolean available() {
        return LOADED;
    }

    public static List<Vec3> currentWorldWaypoints() {
        if (!LOADED) {
            return List.of();
        }
        try {
            List<Vec3> fromMinimap = fromMinimap();
            if (!fromMinimap.isEmpty()) {
                return fromMinimap;
            }
            return fromWorldMap();
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    public static void importInto(DroneEntity drone, List<Vec3> points) {
        if (points.isEmpty()) {
            return;
        }
        NetworkHandler.CHANNEL.sendToServer(new DroneImportWaypointsPacket(drone.getUUID(), points));
    }

    @SuppressWarnings("unchecked")
    private static List<Vec3> fromMinimap() throws Exception {
        Object session = invokeStatic(
                "xaero.common.XaeroMinimapSession", "getCurrentSession");
        if (session == null) {
            session = invokeStatic(
                    "xaero.hud.minimap.module.MinimapSession", "getCurrentSession");
        }
        if (session == null) {
            return List.of();
        }
        Object manager = firstInvoke(session, "getWaypointsManager", "getWaypointManager");
        if (manager == null) {
            return List.of();
        }
        Object set = firstInvoke(manager, "getWaypoints", "getCurrentSet", "getWaypointSet");
        if (set == null) {
            return List.of();
        }
        Object list = firstInvoke(set, "getList", "getWaypoints", "getWaypointList");
        if (!(list instanceof Collection<?> collection)) {
            return List.of();
        }
        return readPoints(collection);
    }

    @SuppressWarnings("unchecked")
    private static List<Vec3> fromWorldMap() throws Exception {
        Object session = invokeStatic("xaero.map.WorldMapSession", "getCurrentSession");
        if (session == null) {
            return List.of();
        }
        Object map = firstInvoke(session, "getMapProcessor", "getWorldMap");
        if (map == null) {
            return List.of();
        }
        Object waypoints = firstInvoke(map, "getWaypoints", "getWaypointSession");
        if (waypoints instanceof Collection<?> collection) {
            return readPoints(collection);
        }
        return List.of();
    }

    private static List<Vec3> readPoints(Collection<?> collection) {
        List<Vec3> out = new ArrayList<>();
        for (Object waypoint : collection) {
            Integer x = intProp(waypoint, "getX", "getWaypointX");
            Integer y = intProp(waypoint, "getY", "getWaypointY");
            Integer z = intProp(waypoint, "getZ", "getWaypointZ");
            if (x == null || y == null || z == null) {
                continue;
            }
            out.add(new Vec3(x + 0.5, y + 1.0, z + 0.5));
            if (out.size() >= 64) {
                break;
            }
        }
        return out.isEmpty() ? Collections.emptyList() : out;
    }

    private static Object invokeStatic(String className, String method) {
        try {
            Class<?> type = Class.forName(className);
            Method m = type.getMethod(method);
            return m.invoke(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object firstInvoke(Object target, String... methods) {
        for (String name : methods) {
            try {
                Method m = target.getClass().getMethod(name);
                return m.invoke(target);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Integer intProp(Object target, String... methods) {
        Object value = firstInvoke(target, methods);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return null;
    }
}
