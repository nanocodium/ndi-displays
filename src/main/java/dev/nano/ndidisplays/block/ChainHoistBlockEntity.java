package dev.nano.ndidisplays.block;

import dev.nano.ndidisplays.NdiDisplays;
import dev.nano.ndidisplays.entity.MovingRigEntity;
import dev.nano.ndidisplays.hoist.HoistConfig;
import dev.nano.ndidisplays.hoist.HoistGroups;
import dev.nano.ndidisplays.hoist.HoistStatus;
import dev.nano.ndidisplays.hoist.RigCollisionDetector;
import dev.nano.ndidisplays.hoist.RigRegistry;
import dev.nano.ndidisplays.hoist.RigStructure;
import dev.nano.ndidisplays.hoist.RigTransform;
import dev.nano.ndidisplays.hoist.ScanResult;
import dev.nano.ndidisplays.hoist.StructureScanner;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A stage chain hoist: the motor half of the rig.
 *
 * The hoist pays chain out and takes it in. What is on the end of the chain is not its
 * problem — {@link StructureScanner} works that out, and a {@link MovingRigEntity} carries
 * it. This class is the controller: limits, speed, a trapezoidal motion profile, and the
 * decision of when it is and is not safe to move.
 *
 * <h3>Chain length is the quantity that matters</h3>
 * Everything is expressed as metres of chain paid out, because that is what a rigger sets
 * and what the motor physically controls. Shorter chain, higher load. The upper limit is
 * the shortest chain allowed, the lower limit the longest.
 *
 * <h3>Every motor runs its own chain</h3>
 * A truss on four motors is one rigid structure, but it is not four motors doing the same
 * thing. Each hoist has its own target and its own limits, and drives its own chain to
 * them; the structure hanging underneath takes up whatever attitude those four chain
 * lengths imply. Take in on one corner and the truss tilts, which is exactly what happens
 * in a venue and is the whole reason the pendant has one motor's buttons on it.
 *
 * <h3>One rig still has one driver</h3>
 * Independent chains do not mean independent physics. One motor of each rig — the lowest
 * position, so the choice is the same whoever asks — is the owner, and once per tick it
 * advances every chain on the rig, fits the plane they describe, tests that plane against
 * the world, and moves the load. Nobody else touches the entity. That is what keeps a
 * four-point hang from being four separate opinions about where the truss is.
 */
public class ChainHoistBlockEntity extends BlockEntity {

    /** Seconds to working speed. Stage motors are soft-started; loads do not like steps. */
    private static final float ACCEL_TIME = 0.4F;
    private static final float DT = 0.05F;

    // --- Motor configuration ---
    private float chainLength = 2.0F;
    private float targetChain = 2.0F;
    private float minChain;
    private float maxChain = 16.0F;
    private float speed = 0.12F;
    private String group = "";

    // --- Runtime ---
    private float prevChain = 2.0F;
    private float velocity;
    private HoistStatus status = HoistStatus.STOPPED;
    /** Why the last attach or detach was refused, so the GUI can say something useful. */
    private ScanResult.Failure lastFailure = ScanResult.Failure.NONE;

    // --- Rig membership ---
    @Nullable
    private UUID rigId;
    private boolean owner;
    /** Chain paid out at the moment of capture; travel is measured from here. */
    private float captureChain;
    /**
     * The block of the load this motor's hook holds, as an offset from the rig origin.
     *
     * This is the point the motor constrains. Two motors holding the same truss at
     * different offsets are what lets it slope, so without this the rig has no geometry at
     * all — only a height.
     */
    @Nullable
    private BlockPos anchorOffset;
    /** Cached for the GUI: what this rig is carrying. */
    private int loadBlocks;
    private int loadMotors;
    /** Cached for the GUI: how far off level the rig is being held, degrees. */
    private float rigTilt;
    /**
     * Set by STOP / a world reload. The load stays an entity only while it is actually
     * travelling — the moment it sits still it has to become world blocks again, or the
     * operator cannot add anything to it.
     */
    private boolean pendingLand;
    /** True after the motor has left its last rest, so arriving at a target triggers a land. */
    private boolean wasMoving;

    public ChainHoistBlockEntity(BlockPos pos, BlockState state) {
        super(NdiDisplays.CHAIN_HOIST_BE.get(), pos, state);
        this.maxChain = Math.min(16.0F, HoistConfig.maxChainLength());
        this.speed = HoistConfig.defaultSpeed();
    }

    // ------------------------------------------------------------------ read-only state

    public float getChainLength() {
        return chainLength;
    }

    /** Chain length for rendering, interpolated between ticks. */
    public float renderChain(float partialTick) {
        return prevChain + (chainLength - prevChain) * partialTick;
    }

    public float getTargetChain() {
        return targetChain;
    }

    public float getMinChain() {
        return minChain;
    }

    public float getMaxChain() {
        return maxChain;
    }

    public float getSpeed() {
        return speed;
    }

    public String getGroup() {
        return group;
    }

    public HoistStatus getStatus() {
        return status;
    }

    public ScanResult.Failure getLastFailure() {
        return lastFailure;
    }

    public boolean isAttached() {
        return rigId != null;
    }

    public boolean isOwner() {
        return owner;
    }

    public int getLoadBlocks() {
        return loadBlocks;
    }

    public int getLoadMotors() {
        return loadMotors;
    }

    /** How far off level the load is being held, degrees. Zero unless it is flying. */
    public float getRigTilt() {
        return rigTilt;
    }

    /** World Y of the hook, in metres. The chain hangs from the motor's underside. */
    public double hookY() {
        return worldPosition.getY() - chainLength;
    }

    /** True when {@code id} is the rig this motor is holding — the ownership handshake. */
    public boolean owns(@Nullable UUID id) {
        return id != null && id.equals(rigId) && owner;
    }

    // ------------------------------------------------------------------ commands

    /** Applies limits, speed and group from the GUI. Movement targets come separately. */
    public void applyConfig(float newMin, float newMax, float newSpeed, String newGroup) {
        float configMax = HoistConfig.maxChainLength();
        this.minChain = Math.max(0, Math.min(newMin, configMax));
        this.maxChain = Math.max(this.minChain, Math.min(newMax, configMax));
        this.speed = Math.max(0.01F, Math.min(newSpeed, HoistConfig.maxSpeed()));

        String normalised = HoistGroups.normalise(newGroup);
        if (!normalised.equals(this.group) && level instanceof ServerLevel server) {
            HoistGroups groups = HoistGroups.get(server);
            groups.leaveAll(worldPosition);
            if (!normalised.isEmpty()) {
                groups.join(normalised, worldPosition);
            }
        }
        this.group = normalised;

        // A limit change can strand the load outside its own range; clamp rather than
        // letting the motor sit permanently at a limit it can never satisfy.
        this.targetChain = clampTarget(this.targetChain);
        sync();
    }

    /**
     * Sends this motor to a chain length.
     *
     * One motor, one chain. If it shares a truss with others they stay where they are and
     * the structure tilts, up to the configured limit. Moving a whole hang level is what
     * the group buttons are for.
     */
    public void commandGoto(float target) {
        if (!isAttached()) {
            tryAutoAttach();
        }
        pendingLand = false;
        if (level instanceof ServerLevel server && rigId != null) {
            // A travel command is also "do not put the load down" — otherwise a follower
            // going DOWN while the owner still has pendingLand would land on the next idle
            // tick and snap every chain back to the capture length.
            driver(server).pendingLand = false;
        }
        if (status.isBlocking()) {
            // Clearing the fault is the operator acknowledging the problem; a new command
            // is that acknowledgement.
            status = HoistStatus.STOPPED;
            lastFailure = ScanResult.Failure.NONE;
        }
        targetChain = clampTarget(target);
        sync();
    }

    /** Runs this motor to its upper limit. */
    public void commandUp() {
        tryAutoAttach();
        commandGoto(minChain);
    }

    /** Runs this motor to its lower limit. */
    public void commandDown() {
        tryAutoAttach();
        commandGoto(maxChain);
    }

    /**
     * Stops every motor on the hang at the length it currently holds.
     *
     * It does not put the load down. A STOP that landed was snapping a four-point hang
     * back to its capture height the moment anyone touched the button — the load never
     * appeared to move, and the chains jumped back to three metres. Set-down is Detach
     * on the owning motor, or the last motor unhooking.
     */
    public void commandStop() {
        haltMotor();
        if (rigId == null || !(level instanceof ServerLevel server)) {
            sync();
            return;
        }
        for (ChainHoistBlockEntity peer : peers(server)) {
            peer.haltMotor();
            peer.sync();
        }
        ChainHoistBlockEntity driver = driver(server);
        driver.pendingLand = false;
        driver.wasMoving = false;
        sync();
        if (driver != this) {
            driver.sync();
        }
    }

    private void haltMotor() {
        targetChain = chainLength;
        velocity = 0;
        if (!status.isBlocking()) {
            status = HoistStatus.STOPPED;
        }
    }

    /**
     * Moves every motor of a group by the same amount of chain.
     *
     * This is the difference between one pendant and the remote. A single motor tilts the
     * truss; a group command shifts the whole hang, so whatever attitude it was in is the
     * attitude it keeps. The distance travelled is the most any member can manage, so the
     * motor that runs out of chain first governs the move rather than being dragged past
     * its own limit.
     *
     * @param up true to take chain in, false to pay it out
     */
    public static void groupMove(List<ChainHoistBlockEntity> motors, boolean up) {
        float allowed = Float.MAX_VALUE;
        for (ChainHoistBlockEntity motor : motors) {
            motor.tryAutoAttach();
            float room = up ? motor.chainLength - motor.minChain
                            : motor.maxChain - motor.chainLength;
            allowed = Math.min(allowed, Math.max(0, room));
        }
        if (allowed == Float.MAX_VALUE || allowed <= 1.0e-4F) {
            // Nobody can move. Let each motor settle at its own limit so the lamps read
            // UPPER_LIMIT / LOWER_LIMIT rather than a silent no-op.
            for (ChainHoistBlockEntity motor : motors) {
                motor.commandGoto(up ? motor.minChain : motor.maxChain);
            }
            return;
        }
        float delta = up ? -allowed : allowed;
        for (ChainHoistBlockEntity motor : motors) {
            motor.commandGoto(motor.chainLength + delta);
        }
    }

    /**
     * Picks up a load if there is a valid island under the hook.
     *
     * Unlike {@link #commandAttach()}, a miss is silent: UP over empty air just runs the
     * chain, and UP over a building does not fault the motor. The Attach button is what
     * reports why a lift was refused.
     */
    private void tryAutoAttach() {
        if (isAttached() || !(level instanceof ServerLevel server)) {
            return;
        }
        if (findJoinableRig(server) != null) {
            commandAttach();
            return;
        }
        BlockPos hook = StructureScanner.findHookTarget(server, worldPosition, maxChain);
        if (hook == null) {
            return;
        }
        RigRegistry registry = RigRegistry.get(server);
        if (registry.rigAt(hook) != null) {
            commandAttach();
            return;
        }
        if (StructureScanner.scan(server, hook, worldPosition).ok()) {
            commandAttach();
        }
    }

    /**
     * Drops the chain, finds what it lands on, and picks it up.
     *
     * Failure here is always a refusal, never a partial lift: the load is either a whole
     * isolated structure within the limits or the motor faults and nothing moves.
     */
    public void commandAttach() {
        if (!(level instanceof ServerLevel server) || isAttached()) {
            return;
        }
        lastFailure = ScanResult.Failure.NONE;

        // The load may already be in the air — another motor of this hang captured it
        // a moment ago, so the world under the hook is empty. Join that rig rather
        // than scanning air and paying out a short unused chain.
        RigRegistry.Rig flying = findJoinableRig(server);
        if (flying != null) {
            joinRig(server, flying);
            return;
        }

        BlockPos hook = StructureScanner.findHookTarget(server, worldPosition, maxChain);
        if (hook == null) {
            fail(ScanResult.Failure.EMPTY);
            return;
        }

        // Somebody else's rig may already be holding these blocks; join it rather than
        // trying to lift a load that is not in the world any more.
        RigRegistry registry = RigRegistry.get(server);
        RigRegistry.Rig existing = registry.rigAt(hook);
        if (existing != null) {
            joinRig(server, existing);
            return;
        }

        ScanResult scan = StructureScanner.scan(server, hook, worldPosition);
        if (!scan.ok()) {
            fail(scan.failure());
            return;
        }

        BlockPos ownerPos = RigRegistry.electOwner(scan.motors());
        if (ownerPos != null && !ownerPos.equals(worldPosition)
                && server.getBlockEntity(ownerPos) instanceof ChainHoistBlockEntity elected) {
            // Two motors on one truss: the elected one does the lifting, always, so there
            // is exactly one capture no matter who was clicked.
            elected.assemble(server, scan);
            return;
        }
        assemble(server, scan);
    }

    /**
     * Unhooks this motor, or puts the whole hang down when this motor is the owner.
     *
     * A follower leaving a four-point hang is the usual press: this chain lets go and
     * the other three keep the truss. The owner is the hang itself, so Detach there
     * sets the load down. The last motor left always lands, because nothing would be
     * holding it up.
     */
    public void commandDetach() {
        if (!(level instanceof ServerLevel server) || rigId == null) {
            return;
        }
        RigRegistry.Rig rig = RigRegistry.get(server).get(rigId);
        if (owner || rig == null || rig.motors().size() <= 1) {
            ChainHoistBlockEntity driver = driver(server);
            driver.pendingLand = true;
            if (!driver.settleLoad(server)) {
                lastFailure = driver.lastFailure;
                sync();
            }
            return;
        }
        dropFromRig(server);
    }

    /**
     * Takes this motor off the hang. Promotes another owner if this one was driving;
     * lands the load when it was the last chain.
     */
    private void dropFromRig(ServerLevel server) {
        RigRegistry registry = RigRegistry.get(server);
        RigRegistry.Rig rig = registry.get(rigId);
        if (rig == null) {
            clearRig();
            return;
        }
        if (rig.motors().size() <= 1) {
            ChainHoistBlockEntity driver = driver(server);
            driver.pendingLand = true;
            if (!driver.settleLoad(server)) {
                lastFailure = driver.lastFailure;
                sync();
            }
            return;
        }

        UUID id = rigId;
        BlockPos next = registry.promote(id, worldPosition);
        clearRig();
        if (next == null) {
            return;
        }
        RigRegistry.Rig left = registry.get(id);
        int remaining = left == null ? 0 : left.motors().size();
        if (server.getBlockEntity(next) instanceof ChainHoistBlockEntity successor) {
            successor.owner = true;
            successor.sync();
        }
        if (left != null) {
            for (BlockPos motor : left.motors()) {
                if (server.getBlockEntity(motor) instanceof ChainHoistBlockEntity be
                        && id.equals(be.rigId)) {
                    be.loadMotors = remaining;
                    be.sync();
                }
            }
        }
    }

    /**
     * Turns the airborne entity back into world blocks.
     *
     * This is the only way the operator can add or remove pieces of the load: while it
     * is an entity, a right-click hits thin air. STOP, a finished move, Detach and a
     * world reload all come through here.
     *
     * @return false when the load has to stay in the air — sloped, or with something
     *         underneath it that was not there when it took off
     */
    private boolean settleLoad(ServerLevel server) {
        if (rigId == null) {
            return true;
        }
        RigRegistry registry = RigRegistry.get(server);
        RigRegistry.Rig rig = registry.get(rigId);
        if (rig == null) {
            clearRig();
            return true;
        }
        List<BlockPos> motors = new ArrayList<>(rig.motors());
        MovingRigEntity entity = findRigEntity(server, rig);
        if (entity != null) {
            List<ChainHoistBlockEntity> crew = crew(server, rig);
            RigTransform held = solveTransform(entity, crew);
            // STOP / Detach always put the load down. A few degrees of leftover rake is
            // four motors that have drifted, not a hang the operator asked to keep; a
            // real rake is levelled here too, because blocks cannot sit on a slope.
            int travel = (int) Math.round(held.travelY());
            levelCrew(crew, travel);
            entity.setTransform(new RigTransform(travel, 0, 0,
                    held.pivotX(), held.pivotY(), held.pivotZ()));
            lastFailure = ScanResult.Failure.NONE;
            rigTilt = 0;

            if (!entity.land(server)) {
                status = HoistStatus.OBSTRUCTED;
                pendingLand = true;
                sync();
                return false;
            }
        }
        for (BlockPos motor : motors) {
            if (server.getBlockEntity(motor) instanceof ChainHoistBlockEntity be) {
                be.clearRig();
            }
        }
        registry.release(rig.rigId());
        pendingLand = false;
        wasMoving = false;
        return true;
    }

    // ------------------------------------------------------------------ rig assembly

    /**
     * Captures the load and puts it in the air. Only ever runs on the elected owner, and
     * only after the registry has agreed no one else holds these blocks.
     */
    private void assemble(ServerLevel server, ScanResult scan) {
        UUID id = UUID.randomUUID();
        RigRegistry registry = RigRegistry.get(server);
        RigRegistry.Rig rig = registry.tryClaim(id, scan.motors(), scan.blocks());
        if (rig == null) {
            // Lost the race to another motor on the same tick. There is a rig holding
            // these blocks now, so become part of it instead of making a second copy.
            RigRegistry.Rig winner = registry.rigAt(scan.blocks().iterator().next());
            if (winner != null) {
                joinRig(server, winner);
            } else {
                fail(ScanResult.Failure.NOT_ISOLATED);
            }
            return;
        }

        BlockPos origin = minCorner(scan.blocks());
        RigStructure structure = RigStructure.capture(server, origin, scan.blocks());
        MovingRigEntity entity = MovingRigEntity.create(server, id, structure);
        server.addFreshEntity(entity);
        rig.setEntityId(entity.getId());

        // Every motor of the rig is attached in the same breath. A motor left out would
        // hold nothing, draw no chain, and leave a four-point hang looking like it is on
        // one — which is precisely what used to happen.
        for (BlockPos motor : rig.motors()) {
            if (server.getBlockEntity(motor) instanceof ChainHoistBlockEntity be) {
                be.adoptRig(id, motor.equals(rig.owner()), structure, RigTransform.IDENTITY,
                        scan.blocks().size(), rig.motors().size());
            }
        }
    }

    /**
     * Attaches this motor to a rig somebody else is already flying.
     *
     * The chain is measured to where the load is <em>now</em>, not where it took off from,
     * so a motor that joins a hang halfway up picks up with its chain the length it looks.
     */
    private void joinRig(ServerLevel server, RigRegistry.Rig rig) {
        MovingRigEntity entity = findRigEntity(server, rig);
        RigStructure structure = entity == null ? null : entity.structure();
        if (entity == null || structure == null) {
            fail(ScanResult.Failure.EMPTY);
            return;
        }
        RigRegistry.get(server).addMotor(rig.rigId(), worldPosition);
        adoptRig(rig.rigId(), rig.owner().equals(worldPosition), structure, entity.transform(),
                structure.size(), rig.motors().size());
    }

    /**
     * Takes hold of a rig.
     *
     * The chain is set to whatever it takes to reach the load where it is standing right
     * now, and that value is also recorded as the capture length, which makes this motor's
     * travel zero at the moment it joins. It therefore asks the rig to stay exactly where
     * it is rather than yanking it to wherever this motor's own trim happened to be.
     */
    private void adoptRig(UUID id, boolean isOwner, RigStructure structure,
                          RigTransform transform, int blocks, int motors) {
        BlockPos anchor = pickAnchor(structure, transform);
        float chainAtCapture = 0;
        if (anchor != null) {
            double topY = transform.apply(anchor.getX() + 0.5, anchor.getY() + 1,
                    anchor.getZ() + 0.5).y + structure.origin().getY();
            chainAtCapture = (float) Math.max(0, worldPosition.getY() - topY);
        }

        // The chain is physically out this far, so the limits have to admit it. Without
        // this a motor mounted higher than its own lower limit allows would take hold of
        // the truss and immediately drive it back down to a number in a text box.
        float configMax = HoistConfig.maxChainLength();
        this.minChain = Math.min(this.minChain, Math.min(chainAtCapture, configMax));
        this.maxChain = Math.max(this.maxChain, Math.min(chainAtCapture, configMax));

        this.rigId = id;
        this.owner = isOwner;
        this.anchorOffset = anchor;
        this.captureChain = chainAtCapture;
        this.chainLength = chainAtCapture;
        this.prevChain = chainAtCapture;
        this.targetChain = chainAtCapture;
        this.velocity = 0;
        this.loadBlocks = blocks;
        this.loadMotors = motors;
        this.rigTilt = transform.tiltDegrees();
        this.status = HoistStatus.STOPPED;
        this.lastFailure = ScanResult.Failure.NONE;
        sync();
    }

    /**
     * The block of the load this motor's chain grabs, as a snapshot offset.
     *
     * Chosen against where the load actually is, so it is the same answer whether the rig
     * is sitting on the deck about to be lifted or already flying at an angle.
     */
    @Nullable
    private BlockPos pickAnchor(RigStructure structure, RigTransform transform) {
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (RigStructure.Entry entry : structure.entries()) {
            BlockPos cell = transform.cellOf(structure.origin(), entry.offset());
            double score = StructureScanner.anchorScore(
                    worldPosition, cell.getX(), cell.getY(), cell.getZ());
            if (score < bestScore) {
                bestScore = score;
                best = entry.offset();
            }
        }
        return best;
    }

    private void clearRig() {
        rigId = null;
        owner = false;
        captureChain = 0;
        anchorOffset = null;
        loadBlocks = 0;
        loadMotors = 0;
        rigTilt = 0;
        velocity = 0;
        targetChain = chainLength;
        if (!status.isBlocking()) {
            status = HoistStatus.STOPPED;
        }
        sync();
    }

    private static BlockPos minCorner(Set<BlockPos> blocks) {
        int x = Integer.MAX_VALUE, y = Integer.MAX_VALUE, z = Integer.MAX_VALUE;
        for (BlockPos pos : blocks) {
            x = Math.min(x, pos.getX());
            y = Math.min(y, pos.getY());
            z = Math.min(z, pos.getZ());
        }
        return new BlockPos(x, y, z);
    }

    // ------------------------------------------------------------------ rig navigation

    /** The motor that drives this rig: the owner, or this motor when standalone. */
    private ChainHoistBlockEntity driver(ServerLevel server) {
        if (rigId == null || owner) {
            return this;
        }
        RigRegistry.Rig rig = RigRegistry.get(server).get(rigId);
        if (rig != null
                && server.getBlockEntity(rig.owner()) instanceof ChainHoistBlockEntity ownerBe
                && ownerBe.rigId != null && ownerBe.rigId.equals(rigId)) {
            return ownerBe;
        }
        // The owner has vanished without handing over. Take the rig rather than leaving
        // the load unattended.
        owner = true;
        return this;
    }

    /**
     * A rig this motor should join rather than starting a second lift: same group already
     * flying, or a claimed cell under / beside the hook.
     */
    @Nullable
    private RigRegistry.Rig findJoinableRig(ServerLevel server) {
        RigRegistry registry = RigRegistry.get(server);
        if (!group.isEmpty()) {
            for (BlockPos member : HoistGroups.get(server).members(group)) {
                if (member.equals(worldPosition) || !server.isLoaded(member)) {
                    continue;
                }
                if (server.getBlockEntity(member) instanceof ChainHoistBlockEntity peer
                        && peer.rigId != null) {
                    RigRegistry.Rig rig = registry.get(peer.rigId);
                    if (rig != null) {
                        return rig;
                    }
                }
            }
        }
        int reach = Math.max(1, Math.round(maxChain)) + 1;
        for (int dy = 0; dy <= reach; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dy == 0 && dx == 0 && dz == 0) {
                        continue;
                    }
                    RigRegistry.Rig rig = registry.rigAt(worldPosition.offset(dx, -dy, dz));
                    if (rig != null) {
                        return rig;
                    }
                }
            }
        }
        return null;
    }

    /**
     * The flying load this motor is holding, if the entity is in range.
     *
     * Used by the chain renderer so the hook can sit on the truss after it has tilted,
     * rather than hanging a vertical chain into empty air.
     */
    @Nullable
    public MovingRigEntity flyingRig() {
        if (rigId == null || level == null) {
            return null;
        }
        double reach = Math.max(chainLength, 8.0) + 64.0;
        for (MovingRigEntity candidate : level.getEntitiesOfClass(MovingRigEntity.class,
                new AABB(worldPosition).inflate(reach))) {
            if (rigId.equals(candidate.rigId())) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * World position of the hook on the flying load this frame, or null when the motor
     * is not attached or the entity is not in range.
     */
    @Nullable
    public Vec3 flyingHookWorld(float partialTick) {
        if (anchorOffset == null) {
            return null;
        }
        MovingRigEntity entity = flyingRig();
        if (entity == null) {
            return null;
        }
        RigTransform tilt = entity.renderTransform();
        Vec3 local = tilt.apply(
                anchorOffset.getX() + 0.5, anchorOffset.getY() + 1.0, anchorOffset.getZ() + 0.5);
        double x = Mth.lerp(partialTick, entity.xo, entity.getX());
        double y = Mth.lerp(partialTick, entity.yo, entity.getY());
        double z = Mth.lerp(partialTick, entity.zo, entity.getZ());
        return new Vec3(x + local.x, y + local.y, z + local.z);
    }

    @Nullable
    private MovingRigEntity findRigEntity(ServerLevel server, RigRegistry.Rig rig) {
        if (rig.entityId() >= 0
                && server.getEntity(rig.entityId()) instanceof MovingRigEntity byId) {
            return byId;
        }
        for (MovingRigEntity candidate : server.getEntitiesOfClass(MovingRigEntity.class,
                new AABB(worldPosition).inflate(HoistConfig.maxChainLength() + 64))) {
            if (rig.rigId().equals(candidate.rigId())) {
                rig.setEntityId(candidate.getId());
                return candidate;
            }
        }
        return null;
    }

    /**
     * Every motor on this rig, this one first and the rest in a fixed order.
     *
     * Determinism matters here: the owner advances all of these chains itself, and a run
     * order that changed between ticks would make a rig's motion depend on hash iteration.
     */
    private List<ChainHoistBlockEntity> crew(ServerLevel server, RigRegistry.Rig rig) {
        List<ChainHoistBlockEntity> out = new ArrayList<>();
        out.add(this);
        out.addAll(peers(server, rig));
        return out;
    }

    private List<ChainHoistBlockEntity> peers(ServerLevel server) {
        if (rigId == null) {
            return List.of();
        }
        RigRegistry.Rig rig = RigRegistry.get(server).get(rigId);
        return rig == null ? List.of() : peers(server, rig);
    }

    private List<ChainHoistBlockEntity> peers(ServerLevel server, RigRegistry.Rig rig) {
        List<ChainHoistBlockEntity> out = new ArrayList<>();
        for (BlockPos motor : rig.motors()) {
            if (motor.equals(worldPosition) || !server.isLoaded(motor)) {
                continue;
            }
            if (server.getBlockEntity(motor) instanceof ChainHoistBlockEntity be
                    && rigId != null && rigId.equals(be.rigId)) {
                out.add(be);
            }
        }
        out.sort(Comparator.comparingLong(be -> be.worldPosition.asLong()));
        return out;
    }

    private float clampTarget(float value) {
        return Math.max(minChain, Math.min(value, maxChain));
    }

    private void fail(ScanResult.Failure failure) {
        lastFailure = failure;
        status = HoistStatus.FAULT;
        velocity = 0;
        targetChain = chainLength;
        sync();
    }

    // ------------------------------------------------------------------ ticking

    /**
     * Runs on both sides. The server owns the position and the safety decisions; the
     * client integrates the same profile so the chain and the load move smoothly between
     * the sparse sync packets rather than stepping once a tick.
     */
    public static void tick(Level level, BlockPos pos, BlockState state,
                            ChainHoistBlockEntity be) {
        be.prevChain = be.chainLength;

        if (level.isClientSide) {
            // Followers are driven by the owner on the server. Integrating them here made
            // the pendant show MOVING DOWN while the load sat still, then STOP snapped
            // the number back to the last synced length.
            if (be.rigId == null || be.owner) {
                be.integrate();
            }
            return;
        }
        be.serverTick((ServerLevel) level);
    }

    private void serverTick(ServerLevel server) {
        if (rigId != null && !owner) {
            // Followers do not integrate: the owner advances their chain for them, in one
            // place, so four motion profiles cannot drift apart between block entity ticks.
            followOwner(server);
            return;
        }

        HoistStatus before = status;
        // Re-clamp every tick, not just when a command arrives: limits can move and a
        // target outside the envelope has to become a stop at the limit rather than a
        // motor that quietly runs past it.
        targetChain = clampTarget(targetChain);

        if (rigId != null) {
            driveRig(server);
        } else {
            integrate();
            updateIdleStatus();
        }

        // The client runs the same profile, so it only needs the state changes plus a
        // periodic correction while moving. Sending the position every tick would put a
        // block update on the wire twenty times a second per motor for no visible gain.
        if (status != before || (status.isMoving() && server.getGameTime() % 20 == 0)) {
            sync();
        }
    }

    /**
     * Advances the whole rig by one tick and moves the load, refusing anything unsafe.
     *
     * Order is the point of this method. Every chain is advanced first, then the plane
     * those chains describe is fitted, then that plane is checked — against the tilt limit
     * and against the world — and only then does the load move. Nothing is committed until
     * the whole rig has somewhere to be, so a corner meeting the ceiling stops the rig
     * rather than tearing it.
     */
    private void driveRig(ServerLevel server) {
        RigRegistry registry = RigRegistry.get(server);
        RigRegistry.Rig rig = registry.get(rigId);
        if (rig == null) {
            // The registry has let go of this rig, so the load has landed and this motor
            // is holding nothing.
            clearRig();
            return;
        }
        MovingRigEntity entity = findRigEntity(server, rig);
        if (entity == null) {
            // The rig is still registered but its entity is not reachable — almost always
            // an unloaded chunk. Hold rather than forgetting the load: dropping the
            // reference here is how a rig would end up owned by nobody.
            velocity = 0;
            targetChain = chainLength;
            return;
        }
        if (!entity.isCarrying()) {
            clearRig();
            return;
        }
        RigStructure structure = entity.structure();
        if (structure == null) {
            return;
        }

        List<ChainHoistBlockEntity> crew = crew(server, rig);
        float[] before = new float[crew.size()];
        HoistStatus[] statusBefore = new HoistStatus[crew.size()];
        for (int i = 0; i < crew.size(); i++) {
            ChainHoistBlockEntity motor = crew.get(i);
            before[i] = motor.chainLength;
            statusBefore[i] = motor.status;
            motor.targetChain = motor.clampTarget(motor.targetChain);
            motor.integrate();
        }

        RigTransform wanted = solveTransform(entity, crew);

        // Tilt limit. Only a move that makes things worse is refused, so a rig that is
        // already over the cap — the limit was lowered, a motor joined at an angle — can
        // still be brought back to level.
        float cap = HoistConfig.maxTiltDegrees();
        if (wanted.tiltDegrees() > cap) {
            RigTransform previous = solveTransform(entity, crew, before);
            if (wanted.tiltDegrees() > previous.tiltDegrees()) {
                holdCrew(crew, before, HoistStatus.OBSTRUCTED);
                wanted = solveTransform(entity, crew);
            }
        }

        Set<BlockPos> footprint =
                RigCollisionDetector.footprint(structure, structure.origin(), wanted);
        if (!footprint.equals(rig.claimed())) {
            if (!RigCollisionDetector.canOccupy(server, footprint, rig.claimed(), rigId)) {
                // Hold everything where it was and wait for the operator to clear the way.
                holdCrew(crew, before, HoistStatus.OBSTRUCTED);
                entity.setTransform(solveTransform(entity, crew));
                syncCrew(server, crew, statusBefore);
                return;
            }
            registry.reclaim(rigId, footprint);
        }

        entity.setTransform(wanted);
        rigTilt = wanted.tiltDegrees();
        for (ChainHoistBlockEntity motor : crew) {
            motor.rigTilt = rigTilt;
            motor.updateIdleStatus();
        }
        syncCrew(server, crew, statusBefore);

        boolean idle = true;
        for (ChainHoistBlockEntity motor : crew) {
            if (Math.abs(motor.velocity) > 1e-4F
                    || Math.abs(motor.chainLength - motor.targetChain) > 1e-3F
                    || motor.status.isMoving()) {
                idle = false;
                break;
            }
        }
        if (idle && pendingLand) {
            if (settleLoad(server)) {
                return;
            }
            // Something has moved in underneath. Keep asking.
            pendingLand = true;
        }
        wasMoving = !idle;
    }

    /**
     * Puts every motor on the same travel so the hang is square, then clamps each chain
     * into its own limits (widening them if a join left a motor outside its box).
     */
    private static void levelCrew(List<ChainHoistBlockEntity> crew, float travel) {
        for (ChainHoistBlockEntity motor : crew) {
            float chain = motor.captureChain - travel;
            float configMax = HoistConfig.maxChainLength();
            motor.minChain = Math.min(motor.minChain, Math.max(0, Math.min(chain, configMax)));
            motor.maxChain = Math.max(motor.maxChain, Math.min(Math.max(chain, motor.minChain), configMax));
            motor.chainLength = chain;
            motor.targetChain = chain;
            motor.velocity = 0;
            motor.rigTilt = 0;
        }
    }

    /** Puts every chain back where it was this tick and stops the ones that had moved. */
    private static void holdCrew(List<ChainHoistBlockEntity> crew, float[] before,
                                 HoistStatus reason) {
        for (int i = 0; i < crew.size(); i++) {
            ChainHoistBlockEntity motor = crew.get(i);
            boolean moved = Math.abs(motor.chainLength - before[i]) > 1e-6F;
            motor.chainLength = before[i];
            motor.targetChain = before[i];
            motor.velocity = 0;
            if (moved && !motor.status.isBlocking()) {
                motor.status = reason;
            }
        }
    }

    private RigTransform solveTransform(MovingRigEntity entity,
                                        List<ChainHoistBlockEntity> crew) {
        return solveTransform(entity, crew, null);
    }

    /**
     * Fits the plane the rig's chains are asking for.
     *
     * Each motor contributes the lift it wants at the point of the load it holds, measured
     * from its own capture length. Lifts rather than absolute heights, so a load that was
     * never level to start with — a stepped truss, a motor bolted into the grid instead of
     * above it — comes off the ground flat.
     *
     * @param chains chain lengths to use instead of the live ones, for testing a move
     *               before committing to it; null to use what the motors currently hold
     */
    private RigTransform solveTransform(MovingRigEntity entity,
                                        List<ChainHoistBlockEntity> crew,
                                        @Nullable float[] chains) {
        List<RigTransform.Sample> samples = new ArrayList<>(crew.size());
        double pivotY = 0;
        int counted = 0;
        for (int i = 0; i < crew.size(); i++) {
            ChainHoistBlockEntity motor = crew.get(i);
            BlockPos anchor = motor.anchorOffset;
            if (anchor == null) {
                continue;
            }
            float chain = chains == null ? motor.chainLength : chains[i];
            samples.add(new RigTransform.Sample(
                    anchor.getX() + 0.5, motor.captureChain - chain, anchor.getZ() + 0.5));
            pivotY += anchor.getY() + 1;
            counted++;
        }
        if (samples.isEmpty()) {
            // No motor knows where it is holding: fall back to the plain vertical move the
            // hoist has always done, driven by this motor alone.
            return new RigTransform(captureChain - chainLength, 0, 0, 0, 0, 0);
        }
        return RigTransform.solve(samples, pivotY / counted);
    }

    /**
     * Tells the followers' clients what changed.
     *
     * Followers are driven by the owner rather than ticked, so this is their only chance to
     * say anything. They get a packet when their lamp changes and a correction once a
     * second while they are running; in between, the client integrates the same motion
     * profile from the same target and draws the chain itself.
     */
    private static void syncCrew(ServerLevel server, List<ChainHoistBlockEntity> crew,
                                 HoistStatus[] statusBefore) {
        for (int i = 0; i < crew.size(); i++) {
            ChainHoistBlockEntity motor = crew.get(i);
            motor.setChanged();
            boolean moving = motor.status.isMoving()
                    || Math.abs(motor.chainLength - motor.targetChain) > 1e-3F;
            if (motor.status != statusBefore[i] || moving) {
                motor.sync();
            }
        }
    }

    /** A follower with no reachable owner promotes itself rather than leaving a rig loose. */
    private void followOwner(ServerLevel server) {
        RigRegistry.Rig rig = RigRegistry.get(server).get(rigId);
        if (rig == null) {
            clearRig();
            return;
        }
        if (!(server.getBlockEntity(rig.owner()) instanceof ChainHoistBlockEntity ownerBe)
                || !rigId.equals(ownerBe.rigId)) {
            owner = true;
            sync();
        }
    }

    private void updateIdleStatus() {
        if (Math.abs(velocity) > 1e-4F) {
            status = velocity < 0 ? HoistStatus.MOVING_UP : HoistStatus.MOVING_DOWN;
            return;
        }
        if (status.isBlocking()) {
            return;
        }
        if (chainLength <= minChain + 1e-3F) {
            status = HoistStatus.UPPER_LIMIT;
        } else if (chainLength >= maxChain - 1e-3F) {
            status = HoistStatus.LOWER_LIMIT;
        } else {
            status = HoistStatus.STOPPED;
        }
    }

    /** One 50 ms slice of the trapezoidal profile: ramp up, cruise, ramp into the target. */
    private void integrate() {
        float target = targetChain;
        float dist = target - chainLength;
        if (Math.abs(dist) < 1e-4F && Math.abs(velocity) < 1e-3F) {
            chainLength = target;
            velocity = 0;
            return;
        }

        float accel = speed / ACCEL_TIME;
        float dir = Math.signum(dist);
        float stopDist = (velocity * velocity) / (2 * accel);
        float desired = (Math.signum(velocity) == dir && stopDist >= Math.abs(dist))
                ? 0 : dir * speed;

        if (velocity < desired) {
            velocity = Math.min(velocity + accel * DT, desired);
        } else if (velocity > desired) {
            velocity = Math.max(velocity - accel * DT, desired);
        }

        float step = velocity * DT;
        if (Math.signum(step) == dir && Math.abs(step) >= Math.abs(dist)) {
            chainLength = target;
            velocity = 0;
            return;
        }
        chainLength += step;
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Called when the motor block is mined.
     *
     * A hoist disappearing must never take its load with it. If other motors are still on
     * the rig, one of them takes over; if this was the last one, the load lands — square,
     * even if it was hanging at an angle, because nothing is holding it up any more.
     */
    public void onBroken() {
        if (!(level instanceof ServerLevel server) || rigId == null) {
            return;
        }
        HoistGroups.get(server).leaveAll(worldPosition);

        RigRegistry registry = RigRegistry.get(server);
        RigRegistry.Rig rig = registry.get(rigId);
        if (rig == null) {
            return;
        }
        BlockPos next = registry.promote(rigId, worldPosition);
        if (next != null
                && server.getBlockEntity(next) instanceof ChainHoistBlockEntity successor) {
            successor.owner = true;
            successor.sync();
            return;
        }
        MovingRigEntity entity = findRigEntity(server, rig);
        if (entity != null) {
            entity.land(server, true);
        }
        registry.release(rigId);
    }

    private void sync() {
        setChanged();
        if (level != null && !level.isClientSide) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, 3);
        }
    }

    // ------------------------------------------------------------------ persistence

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putFloat("Chain", chainLength);
        tag.putFloat("Target", targetChain);
        tag.putFloat("MinChain", minChain);
        tag.putFloat("MaxChain", maxChain);
        tag.putFloat("Speed", speed);
        tag.putString("Group", group);
        tag.putInt("Status", status.ordinal());
        tag.putInt("Failure", lastFailure.ordinal());
        tag.putBoolean("Owner", owner);
        tag.putFloat("CaptureChain", captureChain);
        tag.putInt("LoadBlocks", loadBlocks);
        tag.putInt("LoadMotors", loadMotors);
        tag.putFloat("RigTilt", rigTilt);
        if (anchorOffset != null) {
            tag.putLong("Anchor", anchorOffset.asLong());
        }
        if (rigId != null) {
            tag.putUUID("RigId", rigId);
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        chainLength = tag.getFloat("Chain");
        prevChain = chainLength;
        targetChain = tag.getFloat("Target");
        minChain = tag.getFloat("MinChain");
        maxChain = tag.contains("MaxChain") ? tag.getFloat("MaxChain") : 16.0F;
        speed = tag.contains("Speed") ? tag.getFloat("Speed") : 0.12F;
        group = tag.getString("Group");
        status = HoistStatus.byOrdinal(tag.getInt("Status"));
        ScanResult.Failure[] failures = ScanResult.Failure.values();
        int failureOrdinal = tag.getInt("Failure");
        lastFailure = failureOrdinal >= 0 && failureOrdinal < failures.length
                ? failures[failureOrdinal] : ScanResult.Failure.NONE;
        owner = tag.getBoolean("Owner");
        captureChain = tag.getFloat("CaptureChain");
        loadBlocks = tag.getInt("LoadBlocks");
        loadMotors = tag.getInt("LoadMotors");
        rigTilt = tag.getFloat("RigTilt");
        anchorOffset = tag.contains("Anchor") ? BlockPos.of(tag.getLong("Anchor")) : null;
        rigId = tag.hasUUID("RigId") ? tag.getUUID("RigId") : null;
        velocity = 0;
    }

    /**
     * Nothing moves through a reload.
     *
     * The rig and its height survive — that is the point of persisting them — but a motor
     * that was travelling comes back stopped, holding its load exactly where it was, and
     * waits for someone to press a button. A hoist that resumed a move on its own after a
     * restart would be a hoist nobody was watching.
     */
    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) {
            velocity = 0;
            targetChain = chainLength;
            if (status.isMoving()) {
                status = HoistStatus.STOPPED;
            }
            // A reload must never leave the load as an uneditable entity. Repose on the
            // next tick, once the MovingRigEntity is back in the world. A rig that was
            // hanging at an angle stays up until somebody levels it.
            if (rigId != null && owner) {
                pendingLand = true;
            }
        }
    }

    @Override
    public CompoundTag getUpdateTag() {
        return saveWithoutMetadata();
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket pkt) {
        if (pkt.getTag() != null) {
            float before = chainLength;
            load(pkt.getTag());
            prevChain = before;
        }
    }

    @Override
    public AABB getRenderBoundingBox() {
        // The chain is drawn all the way down to the hook, which can be well outside the
        // motor's own block — and off to one side once the load is raked.
        Vec3 hook = flyingHookWorld(1.0F);
        if (hook != null) {
            return new AABB(worldPosition).minmax(new AABB(hook, hook)).inflate(1.0);
        }
        return new AABB(worldPosition).inflate(Math.max(4.0, chainLength + 2.0));
    }
}
