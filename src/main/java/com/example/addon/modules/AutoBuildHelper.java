package com.example.addon.modules;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalInverted;
import baritone.api.pathing.goals.GoalNear;
import net.minecraft.block.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.state.property.Property;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Helper cho AutoBuild. CHƯA BIÊN DỊCH.
 *  - Baritone: đi tới gần block, tắt allowPlace/allowBreak để Baritone không tự đặt/phá block.
 *  - plan(): "Easy Place bằng mô phỏng" – thử nhiều góc xoay + điểm click, gọi
 *    block.getPlacementState() cho tới khi ra đúng trạng thái schematic cần.
 *  - place(): gửi packet xoay rồi interactBlock (cách Baritone/vanilla đặt block).
 */
public final class AutoBuildHelper {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    /** Kế hoạch đặt 1 block: click vào `neighbor` mặt `face` tại `hit`, với góc nhìn yaw/pitch. */
    public record Plan(BlockPos neighbor, Direction face, Vec3d hit, float yaw, float pitch, boolean interactable) {}

    /** Thuộc tính do hàng xóm/thế giới quyết định hoặc không đặt trực tiếp được -> bỏ qua khi so sánh. */
    private static final Set<String> IGNORED = Set.of(
        "waterlogged", "powered", "lit", "shape", "north", "east", "south", "west", "up", "down",
        "power", "distance", "persistent", "triggered", "locked", "delay", "mode", "inverted",
        "open", "hinge", "bites", "age");

    // ------------------------------------------------------------ Baritone

    private static boolean saved;
    private static boolean oBreak, oPlace, oParkour;
    private static long lastMoveMs;

    private static IBaritone baritone() {
        return BaritoneAPI.getProvider().getPrimaryBaritone();
    }

    /** Gọi khi bật module: Baritone không được tự đặt/phá/nhảy parkour (nguyên nhân block lạ xuất hiện). */
    public static void prepareBaritone() {
        var s = BaritoneAPI.getSettings();
        if (!saved) {
            oBreak = s.allowBreak.value;
            oPlace = s.allowPlace.value;
            oParkour = s.allowParkour.value;
            saved = true;
        }
        s.allowBreak.value = false;
        s.allowPlace.value = false;
        s.allowParkour.value = false;
    }

    /** Gọi khi tắt module. */
    public static void restoreBaritone() {
        if (!saved) return;
        var s = BaritoneAPI.getSettings();
        s.allowBreak.value = oBreak;
        s.allowPlace.value = oPlace;
        s.allowParkour.value = oParkour;
        saved = false;
    }

    public static void moveNear(BlockPos target, double reach) {
        IBaritone b = baritone();
        long now = System.currentTimeMillis();
        if (b.getPathingBehavior().isPathing() || now - lastMoveMs < 500) return;
        lastMoveMs = now;
        b.getCustomGoalProcess().setGoalAndPath(new GoalNear(target, Math.max(1, (int) reach)));
    }

    /** Đi ra xa khỏi `from` (khi block cần đặt nằm đúng chỗ người chơi đứng). */
    public static void moveAway(BlockPos from) {
        IBaritone b = baritone();
        long now = System.currentTimeMillis();
        if (b.getPathingBehavior().isPathing() || now - lastMoveMs < 1000) return;
        lastMoveMs = now;
        b.getCustomGoalProcess().setGoalAndPath(new GoalInverted(new GoalNear(from, 3)));
    }

    public static boolean isMoving() {
        return baritone().getPathingBehavior().isPathing();
    }

    public static void stopMoving() {
        baritone().getPathingBehavior().cancelEverything();
    }

    public static boolean inReach(BlockPos pos, double range) {
        ClientPlayerEntity p = mc.player;
        if (p == null) return false;
        return p.getEyePos().squaredDistanceTo(Vec3d.ofCenter(pos)) <= range * range;
    }

    // ------------------------------------------------------------ lập kế hoạch đặt

    /** So sánh trạng thái đạt được với trạng thái cần, bỏ qua các thuộc tính trong IGNORED. */
    public static boolean matches(BlockState res, BlockState target) {
        if (res.getBlock() != target.getBlock()) return false;
        for (Property<?> p : target.getProperties()) {
            String n = p.getName();
            if (IGNORED.contains(n)) continue;
            Object want = target.get(p);
            if (n.equals("type") && (target.getBlock() instanceof AbstractChestBlock || want.toString().equals("double"))) continue;
            if (!res.get(p).equals(want)) return false;
        }
        return true;
    }

    /** Trả về null nếu không có cách đặt (thiếu điểm tựa, hoặc không đạt được hướng cần). */
    public static Plan plan(BlockPos pos, BlockState target, ItemStack stack, boolean strict) {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) return null;

        Direction[] order = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.UP};
        float oy = p.getYaw(), op = p.getPitch();
        try {
            for (Direction dir : order) {
                BlockPos nb = pos.offset(dir);
                BlockState ns = mc.world.getBlockState(nb);
                if (ns.isReplaceable()) continue; // cần điểm tựa đặc
                Direction face = dir.getOpposite();
                boolean horizontal = dir.getAxis().isHorizontal();
                double[] ys = (horizontal && strict) ? new double[]{0.25, 0.75} : new double[]{0.5};
                for (double hy : ys) {
                    Vec3d hit = horizontal
                        ? new Vec3d(nb.getX() + 0.5 + face.getOffsetX() * 0.5, nb.getY() + hy, nb.getZ() + 0.5 + face.getOffsetZ() * 0.5)
                        : Vec3d.ofCenter(nb).add(face.getOffsetX() * 0.5, face.getOffsetY() * 0.5, face.getOffsetZ() * 0.5);
                    for (float[] rot : candidates(hit, strict)) {
                        p.setYaw(rot[0]);
                        p.setPitch(rot[1]);
                        ItemPlacementContext ctx = new ItemPlacementContext(p, Hand.MAIN_HAND, stack,
                            new BlockHitResult(hit, face, nb, false));
                        if (!ctx.canPlace()) continue;
                        BlockState res = target.getBlock().getPlacementState(ctx);
                        if (res != null && res.canPlaceAt(mc.world, pos) && (!strict ? res.getBlock() == target.getBlock() : matches(res, target))) {
                            return new Plan(nb, face, hit, rot[0], rot[1], isInteractable(ns));
                        }
                    }
                }
            }
        } finally {
            p.setYaw(oy);
            p.setPitch(op);
        }
        return null;
    }

    /** Ứng viên góc nhìn: nhìn thẳng vào điểm click trước, rồi 4 hướng chính x vài độ ngẩng/cúi. */
    private static List<float[]> candidates(Vec3d hit, boolean strict) {
        List<float[]> l = new ArrayList<>();
        Vec3d eye = mc.player.getEyePos();
        double dx = hit.x - eye.x, dy = hit.y - eye.y, dz = hit.z - eye.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        l.add(new float[]{
            (float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI) - 90f,
            (float) -(MathHelper.atan2(dy, h) * 180.0 / Math.PI)});
        if (!strict) return l; // không cần hướng: chỉ nhìn vào điểm click
        for (float pitch : new float[]{0f, 60f, -60f, 90f, -90f}) {
            for (Direction d : Direction.Type.HORIZONTAL) l.add(new float[]{d.asRotation(), pitch});
        }
        return l;
    }

    private static boolean isInteractable(BlockState s) {
        Block b = s.getBlock();
        return s.hasBlockEntity() || b instanceof DoorBlock || b instanceof TrapdoorBlock
            || b instanceof FenceGateBlock || b instanceof ButtonBlock || b instanceof LeverBlock
            || b instanceof CraftingTableBlock || b instanceof AnvilBlock || b instanceof BedBlock
            || b instanceof NoteBlock || b instanceof RepeaterBlock || b instanceof ComparatorBlock;
    }

    // ------------------------------------------------------------ đặt block

    /**
     * Thực hiện plan. Luôn gửi packet xoay đúng góc của plan ngay trước khi click để server
     * dùng đúng hướng (không bị trễ 1 tick như camera).
     *
     * @param sneakAlways  luôn sneak khi đặt
     * @param sneakAuto    chỉ sneak khi điểm tựa là block có thể tương tác (rương, cửa, nút...)
     */
    public static boolean place(Plan plan, boolean rotate, boolean sneakAlways, boolean sneakAuto) {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.interactionManager == null) return false;
        boolean sneak = sneakAlways || (sneakAuto && plan.interactable());
        float oy = p.getYaw(), op = p.getPitch();
        try {
            if (rotate) {
                p.setYaw(plan.yaw());
                p.setPitch(plan.pitch());
                sendRotation(plan.yaw(), plan.pitch());
            }
            if (sneak) sendSneak(true);
            ActionResult r = mc.interactionManager.interactBlock(p, Hand.MAIN_HAND,
                new BlockHitResult(plan.hit(), plan.face(), plan.neighbor(), false));
            if (r.isAccepted()) p.swingHand(Hand.MAIN_HAND);
            return r.isAccepted();
        } finally {
            if (sneak) sendSneak(false);
            if (rotate) {
                p.setYaw(oy);
                p.setPitch(op);
            }
        }
    }

    public static void sendRotation(float yaw, float pitch) {
        ClientPlayerEntity p = mc.player;
        mc.getNetworkHandler().sendPacket(
            new PlayerMoveC2SPacket.LookAndOnGround(yaw, pitch, p.isOnGround(), p.horizontalCollision));
    }

    public static void sendSneak(boolean on) {
        mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(
            mc.player,
            on ? ClientCommandC2SPacket.Mode.PRESS_SHIFT_KEY : ClientCommandC2SPacket.Mode.RELEASE_SHIFT_KEY));
    }
}
