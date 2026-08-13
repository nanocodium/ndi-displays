package dev.nano.ndidisplays.compat.theatrical;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

/**
 * Everything the flown-fixture renderer needs from a Theatrical fixture, expressed
 * in vanilla types only, so the renderer never touches a Theatrical class. Resolved
 * once per fixture block id by {@link TheatricalCompat#fixtureModelData}.
 *
 * @param state       the fixture block's default state (model rendering context)
 * @param staticModel model location of the non-moving body (yoke base)
 * @param panModel    model location of the pan stage, null when the fixture has none
 * @param tiltModel   model location of the tilt stage (the head), null when absent
 * @param panPivot    xyz pivot the pan stage rotates around, block-local
 * @param tiltPivot   xyz pivot the tilt stage rotates around, block-local
 * @param beamStart   xyz the beam leaves the head, block-local
 * @param beamWidth   half-width of the beam at the lens
 */
public record FixtureModelData(BlockState state,
                               ResourceLocation staticModel,
                               @Nullable ResourceLocation panModel,
                               @Nullable ResourceLocation tiltModel,
                               float[] panPivot,
                               float[] tiltPivot,
                               float[] beamStart,
                               float beamWidth) {
}
