"""Mirror of RigTransform.solve/apply, to check the algebra outside Minecraft.

Kept alongside the mod because the plane fit is the one part of the hoist whose sign
conventions cannot be eyeballed: a flipped axis looks like a truss that rakes the wrong
way, which is easy to miss in game and obvious here.
"""

import math

FLAT_GRADIENT = 0.04


def solve(samples, pivot_y):
    """samples: list of (x, lift, z). Returns (travel_y, grad_x, grad_z, px, py, pz)."""
    n = len(samples)
    cx = sum(s[0] for s in samples) / n
    cy = sum(s[1] for s in samples) / n
    cz = sum(s[2] for s in samples) / n

    sxx = sxz = szz = sxy = szy = 0.0
    for x, lift, z in samples:
        dx, dz, dy = x - cx, z - cz, lift - cy
        sxx += dx * dx
        sxz += dx * dz
        szz += dz * dz
        sxy += dx * dy
        szy += dz * dy

    ridge = 1.0e-6 * (sxx + szz) + 1.0e-9
    sxx += ridge
    szz += ridge

    det = sxx * szz - sxz * sxz
    gx = gz = 0.0
    if abs(det) > 1.0e-12:
        gx = (sxy * szz - szy * sxz) / det
        gz = (szy * sxx - sxy * sxz) / det
    return (cy, gx, gz, cx, pivot_y, cz)


def apply(t, local):
    travel_y, gx, gz, px, py, pz = t
    lx, ly, lz = local
    dx, dy, dz = lx - px, ly - py, lz - pz

    mag = math.hypot(gx, gz)
    if mag < 1.0e-9:
        return (lx, ly + travel_y, lz)

    angle = math.atan(mag)
    cos, sin = math.cos(angle), math.sin(angle)
    nx, nz = -gz / mag, gx / mag

    dot = nx * dx + nz * dz
    cross_x = -nz * dy
    cross_y = nz * dx - nx * dz
    cross_z = nx * dy

    rx = dx * cos + cross_x * sin + nx * dot * (1 - cos)
    ry = dy * cos + cross_y * sin
    rz = dz * cos + cross_z * sin + nz * dot * (1 - cos)
    return (px + rx, py + ry + travel_y, pz + rz)


def tilt_degrees(t):
    return math.degrees(math.atan(math.hypot(t[1], t[2])))


def flat(t):
    return abs(t[1]) <= FLAT_GRADIENT and abs(t[2]) <= FLAT_GRADIENT


def check(label, got, want, tol=1e-3):
    ok = all(abs(a - b) <= tol for a, b in zip(got, want)) if hasattr(got, "__len__") \
        else abs(got - want) <= tol
    print(f"{'ok  ' if ok else 'FAIL'} {label}: got {got}, want {want}")
    return ok


passed = True

# --- 1. Nothing has moved: a load whose motors are all at their capture length is flat.
# Anchors at different heights on purpose (a stepped truss, a motor bolted in the grid).
t = solve([(0.5, 0.0, 0.5), (10.5, 0.0, 0.5), (0.5, 0.0, 6.5)], 5.0)
passed &= check("still: travel", t[0], 0.0)
passed &= check("still: gradient", (t[1], t[2]), (0.0, 0.0))
passed &= check("still: flat", 1 if flat(t) else 0, 1)
passed &= check("still: a block does not move", apply(t, (3.0, 4.0, 2.0)), (3.0, 4.0, 2.0))

# --- 2. One motor, one metre in. Pure translation, whatever the anchor geometry.
t = solve([(0.5, 1.0, 0.5)], 5.0)
passed &= check("single: travel", t[0], 1.0)
passed &= check("single: gradient", (t[1], t[2]), (0.0, 0.0))
passed &= check("single: block rises 1", apply(t, (7.0, 2.0, 3.0)), (7.0, 3.0, 3.0))

# --- 3. Two motors ten blocks apart on X, the one at x=0.5 takes in a metre.
# Slope must fall towards +x, and each hook must end up at the height its motor asked for.
t = solve([(0.5, 1.0, 0.5), (10.5, 0.0, 0.5)], 5.0)
passed &= check("rake: travel is the mean lift", t[0], 0.5)
passed &= check("rake: gradient falls along +x", t[1], -0.1)
passed &= check("rake: no slope across z", t[2], 0.0)
passed &= check("rake: angle", tilt_degrees(t), math.degrees(math.atan(0.1)))
passed &= check("rake: not flat", 1 if flat(t) else 0, 0)
# The lifted end near 6 and the held end near 5, within the centimetre a rigid turn
# shortens the horizontal span by.
lifted = apply(t, (0.5, 5.0, 0.5))
held = apply(t, (10.5, 5.0, 0.5))
passed &= check("rake: lifted hook", lifted[1], 6.0, tol=0.01)
passed &= check("rake: held hook", held[1], 5.0, tol=0.01)
passed &= check("rake: hooks stay put horizontally", (lifted[0], held[0]), (0.5, 10.5), tol=0.03)

# --- 4. Same again on Z, to catch a swapped axis.
t = solve([(0.5, 1.0, 0.5), (0.5, 0.0, 10.5)], 5.0)
passed &= check("rake z: gradient falls along +z", t[2], -0.1)
passed &= check("rake z: no slope across x", t[1], 0.0)
passed &= check("rake z: lifted hook", apply(t, (0.5, 5.0, 0.5))[1], 6.0, tol=0.01)

# --- 5. Four motors on a rectangle, one corner up two metres. Least squares takes the
# best plane through four demands rather than tearing the truss.
corners = [(0.5, 0.5), (12.5, 0.5), (0.5, 8.5), (12.5, 8.5)]
lifts = [2.0, 0.0, 0.0, 0.0]
t = solve([(x, lift, z) for (x, z), lift in zip(corners, lifts)], 5.0)
passed &= check("corner: travel is the mean lift", t[0], 0.5)
# Least squares on a rectangle: the cross term vanishes, so each gradient is that axis'
# moment over its own spread. sxy/sxx = -12/144 and szy/szz = -8/64. The single raised
# corner therefore pulls the plane on both axes, and the other three share the difference
# rather than one of them being ignored.
passed &= check("corner: falls along +x", t[1], -12.0 / 144.0, tol=1e-5)
passed &= check("corner: falls along +z", t[2], -8.0 / 64.0, tol=1e-5)
# No corner ends up more than half the demand away from the plane, which is what "the
# truss does not tear" means in practice.
raised = apply(t, (0.5, 5.0, 0.5))[1]
passed &= check("corner: raised corner follows the plane", raised, 5.0 + 1.5, tol=0.05)
print(f"     corner: rake is {tilt_degrees(t):.1f} degrees")

# --- 6. Collinear motors must not blow the fit up: three in a line on X give slope along
# X and none across Z, rather than a singular system.
t = solve([(0.5, 0.0, 4.5), (6.5, 0.5, 4.5), (12.5, 1.0, 4.5)], 5.0)
passed &= check("collinear: slope along x", t[1], 1.0 / 12.0, tol=1e-4)
passed &= check("collinear: nothing across z", t[2], 0.0, tol=1e-9)

# --- 7. Rigid: a turn preserves distance between blocks of the load.
t = solve([(0.5, 3.0, 0.5), (12.5, 0.0, 0.5)], 5.0)
a = apply(t, (1.0, 5.0, 1.0))
b = apply(t, (11.0, 5.0, 1.0))
passed &= check("rigid: span preserved", math.dist(a, b), 10.0, tol=1e-9)
print(f"     rigid: rake is {tilt_degrees(t):.1f} degrees")

print("\nALL CHECKS PASSED" if passed else "\nSOME CHECKS FAILED")
raise SystemExit(0 if passed else 1)
