package dev.nano.ndidisplays.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.logging.LogUtils;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * A bundled OBJ mesh split into its named parts, for renderers that animate — a PTZ yoke that
 * pans, a camera body that tilts, rotor blades that spin. Forge's OBJ loader bakes a model once,
 * statically; this keeps each {@code o}-group separate so a block entity renderer can draw any
 * subset under its own pose transform, which is exactly what articulated rigs need.
 *
 * Geometry is baked at load into packed triangle arrays (position, UV, normal per vertex) and
 * emitted as degenerate quads, since entity render types batch quads. Meshes live in
 * {@code assets/ndidisplays/meshes/}; their atlases are ordinary textures.
 */
public final class ObjPartMesh {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<String, ObjPartMesh> CACHE = new ConcurrentHashMap<>();

    /** name -> packed vertices: x,y,z,u,v,nx,ny,nz per vertex, three per triangle. */
    private final Map<String, float[]> parts;

    private ObjPartMesh(Map<String, float[]> parts) {
        this.parts = parts;
    }

    public static ObjPartMesh get(String name) {
        return CACHE.computeIfAbsent(name, ObjPartMesh::load);
    }

    private static ObjPartMesh load(String name) {
        Map<String, float[]> parts = new HashMap<>();
        String path = "/assets/ndidisplays/meshes/" + name + ".obj";
        try (InputStream in = ObjPartMesh.class.getResourceAsStream(path)) {
            if (in == null) {
                LOGGER.warn("[ndidisplays] mesh {} missing", path);
                return new ObjPartMesh(parts);
            }
            List<float[]> vs = new ArrayList<>();
            List<float[]> vts = new ArrayList<>();
            List<float[]> vns = new ArrayList<>();
            Map<String, List<Float>> groups = new HashMap<>();
            String group = "default";
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("v ")) {
                    String[] p = line.split("\\s+");
                    vs.add(new float[]{Float.parseFloat(p[1]), Float.parseFloat(p[2]),
                            Float.parseFloat(p[3])});
                } else if (line.startsWith("vt ")) {
                    String[] p = line.split("\\s+");
                    vts.add(new float[]{Float.parseFloat(p[1]), Float.parseFloat(p[2])});
                } else if (line.startsWith("vn ")) {
                    String[] p = line.split("\\s+");
                    vns.add(new float[]{Float.parseFloat(p[1]), Float.parseFloat(p[2]),
                            Float.parseFloat(p[3])});
                } else if (line.startsWith("o ") || line.startsWith("g ")) {
                    group = line.split("\\s+")[1];
                } else if (line.startsWith("f ")) {
                    String[] p = line.split("\\s+");
                    List<Float> out = groups.computeIfAbsent(group, k -> new ArrayList<>());
                    // fan-triangulate; exporters hand us tris and quads
                    for (int i = 2; i < p.length - 1; i++) {
                        emit(out, vs, vts, vns, p[1]);
                        emit(out, vs, vts, vns, p[i]);
                        emit(out, vs, vts, vns, p[i + 1]);
                    }
                }
            }
            for (Map.Entry<String, List<Float>> e : groups.entrySet()) {
                float[] arr = new float[e.getValue().size()];
                for (int i = 0; i < arr.length; i++) {
                    arr[i] = e.getValue().get(i);
                }
                parts.put(e.getKey(), arr);
            }
            LOGGER.info("[ndidisplays] mesh {} loaded: {} parts", name, parts.size());
        } catch (Exception e) {
            LOGGER.warn("[ndidisplays] mesh {} failed to load: {}", name, e.toString());
        }
        return new ObjPartMesh(parts);
    }

    private static void emit(List<Float> out, List<float[]> vs, List<float[]> vts,
                             List<float[]> vns, String token) {
        String[] idx = token.split("/");
        float[] v = vs.get(Integer.parseInt(idx[0]) - 1);
        float[] t = idx.length > 1 && !idx[1].isEmpty()
                ? vts.get(Integer.parseInt(idx[1]) - 1) : new float[]{0, 0};
        float[] n = idx.length > 2 && !idx[2].isEmpty()
                ? vns.get(Integer.parseInt(idx[2]) - 1) : new float[]{0, 1, 0};
        out.add(v[0]);
        out.add(v[1]);
        out.add(v[2]);
        out.add(t[0]);
        out.add(1.0F - t[1]); // OBJ v origin is bottom-left; textures sample top-down
        out.add(n[0]);
        out.add(n[1]);
        out.add(n[2]);
    }

    /** Draws every part whose name the predicate accepts, under the current pose. */
    public void render(PoseStack pose, VertexConsumer vc, int light, Predicate<String> which) {
        PoseStack.Pose p = pose.last();
        for (Map.Entry<String, float[]> e : parts.entrySet()) {
            if (!which.test(e.getKey())) {
                continue;
            }
            float[] a = e.getValue();
            for (int i = 0; i < a.length; i += 24) {
                // one triangle as a degenerate quad (entity render types batch quads)
                vertex(p, vc, light, a, i);
                vertex(p, vc, light, a, i + 8);
                vertex(p, vc, light, a, i + 16);
                vertex(p, vc, light, a, i + 16);
            }
        }
    }

    private static void vertex(PoseStack.Pose p, VertexConsumer vc, int light, float[] a, int o) {
        vc.vertex(p.pose(), a[o], a[o + 1], a[o + 2])
                .color(255, 255, 255, 255)
                .uv(a[o + 3], a[o + 4])
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(light)
                .normal(p.normal(), a[o + 5], a[o + 6], a[o + 7])
                .endVertex();
    }
}
