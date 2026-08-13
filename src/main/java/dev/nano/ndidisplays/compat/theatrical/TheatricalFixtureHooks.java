package dev.nano.ndidisplays.compat.theatrical;

import dev.imabad.theatrical.api.Fixture;
import dev.imabad.theatrical.api.FixtureProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;

/**
 * Client-side Theatrical access for the flown fixture payload. Only classloaded when
 * Theatrical is present (see {@link TheatricalCompat}).
 *
 * Theatrical's renderers are anchored on a placed block entity, which a flown
 * fixture doesn't have — so this resolves the {@link Fixture} definition through a
 * throwaway block entity created from the block's default state (every Theatrical
 * light BE returns a static fixture definition, no level access involved), and hands
 * the model locations/pivots back as vanilla-typed {@link FixtureModelData}.
 */
final class TheatricalFixtureHooks {

    private TheatricalFixtureHooks() {
    }

    @Nullable
    static FixtureModelData resolve(String blockId) {
        ResourceLocation id = ResourceLocation.tryParse(blockId);
        if (id == null) {
            return null;
        }
        Block block = ForgeRegistries.BLOCKS.getValue(id);
        if (block == null || !(block instanceof EntityBlock entityBlock)) {
            return null;
        }
        BlockState state = block.defaultBlockState();
        BlockEntity be = entityBlock.newBlockEntity(BlockPos.ZERO, state);
        if (!(be instanceof FixtureProvider provider)) {
            return null;
        }
        Fixture fixture = provider.getFixture();
        if (fixture == null) {
            return null;
        }
        return new FixtureModelData(state,
                fixture.getStaticModel(),
                fixture.hasPanModel() ? fixture.getPanModel() : null,
                fixture.hasTiltModel() ? fixture.getTiltModel() : null,
                fixture.getPanRotationPosition(),
                fixture.getTiltRotationPosition(),
                fixture.getBeamStartPosition(),
                fixture.getBeamWidth());
    }
}
