package com.example.addon.modules;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.item.Item;
import net.minecraft.nbt.*;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

import java.io.File;
import java.util.*;

/**
 * Viết lại từ đầu (không có mã nguồn gốc). CHƯA BIÊN DỊCH.
 * Nạp .schem (Sponge v2/v3) và .nbt, xây từ thấp lên cao, tự đi bằng Baritone,
 * đặt đúng hướng bằng mô phỏng placement, xoay camera mượt theo từng frame.
 */
public class AutoBuild extends Module {
    public enum SneakMode { Auto, Always, Never }
    public enum RotationMode { Smooth, Silent, Off }

    private static final class BuildTask {
        final BlockPos pos;
        final BlockState state;
        AutoBuildHelper.Plan plan;
        long planUntil, retryAt;
        int attempts;
        BuildTask(BlockPos pos, BlockState state) { this.pos = pos; this.state = state; }
    }

    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<String> schematicPath = sg.add(new StringSetting.Builder()
        .name("schematic-file").description("Đường dẫn đầy đủ tới file .schem hoặc .nbt.")
        .defaultValue("").build());
    private final Setting<Integer> maxPerTick = sg.add(new IntSetting.Builder()
        .name("max-placements-per-tick").defaultValue(4).min(1).sliderMax(10).build());
    private final Setting<Integer> minDelayMs = sg.add(new IntSetting.Builder()
        .name("min-delay-ms").defaultValue(100).min(0).sliderMax(1000).build());
    private final Setting<Integer> maxDelayMs = sg.add(new IntSetting.Builder()
        .name("max-delay-ms").defaultValue(500).min(0).sliderMax(2000).build());
    private final Setting<Double> curvePower = sg.add(new DoubleSetting.Builder()
        .name("delay-curve-power").description("Lớn hơn = thiên về delay ngắn.")
        .defaultValue(2.0).min(0.1).sliderMax(5).build());
    private final Setting<Double> range = sg.add(new DoubleSetting.Builder()
        .name("range").defaultValue(4.5).min(1).sliderMax(6).build());
    private final Setting<RotationMode> rotationMode = sg.add(new EnumSetting.Builder<RotationMode>()
        .name("rotation-mode")
        .description("Smooth: camera xoay mượt thật. Silent: chỉ gửi packet, camera đứng yên. Off: không xoay.")
        .defaultValue(RotationMode.Smooth).build());
    private final Setting<Double> rotationSpeed = sg.add(new DoubleSetting.Builder()
        .name("rotation-speed").description("Tốc độ xoay tối đa (độ/giây) ở chế độ Smooth.")
        .defaultValue(360.0).min(60).sliderMax(1080).build());
    private final Setting<Boolean> ignoreOrientation = sg.add(new BoolSetting.Builder()
        .name("ignore-orientation")
        .description("Chỉ đặt đúng loại block, không tự tính hướng/powered (dùng khi đã có mod Easy Redstone/Easy Place).")
        .defaultValue(true).build());
    private final Setting<Boolean> autoMove = sg.add(new BoolSetting.Builder()
        .name("auto-move").description("Tự đi tới block tiếp theo bằng Baritone.").defaultValue(true).build());
    private final Setting<SneakMode> sneakMode = sg.add(new EnumSetting.Builder<SneakMode>()
        .name("sneak-mode").description("Auto: chỉ sneak khi click vào rương/cửa/nút...")
        .defaultValue(SneakMode.Auto).build());
    private final Setting<Integer> originX = sg.add(new IntSetting.Builder()
        .name("origin-x").defaultValue(0).noSlider().build());
    private final Setting<Integer> originY = sg.add(new IntSetting.Builder()
        .name("origin-y").defaultValue(64).noSlider().build());
    private final Setting<Integer> originZ = sg.add(new IntSetting.Builder()
        .name("origin-z").defaultValue(0).noSlider().build());
    private final Setting<Integer> resortTicks = sg.add(new IntSetting.Builder()
        .name("resort-interval-ticks").defaultValue(40).min(5).sliderMax(200).build());

    private final List<BuildTask> queue = new ArrayList<>();
    private volatile List<BuildTask> loaded;
    private final Random random = new Random();
    private long nextPlaceAt, lastReport, lastFrameNs;
    private int tick, differing, occupied, gaveUp;
    private boolean wantRot;
    private float wantYaw, wantPitch;

    public AutoBuild() {
        super(Categories.World, "auto-build", "Tự động xây theo schematic (.schem, .nbt).");
    }

    // ------------------------------------------------------------ vòng đời

    @Override
    public void onActivate() {
        queue.clear();
        loaded = null;
        wantRot = false;
        differing = occupied = gaveUp = 0;
        File f = new File(schematicPath.get().trim().replace("\"", ""));
        if (!f.isFile()) { error("Không tìm thấy file schematic: " + f); toggle(); return; }
        BlockPos origin = new BlockPos(originX.get(), originY.get(), originZ.get());
        try { AutoBuildHelper.prepareBaritone(); } catch (Throwable e) { error("Baritone lỗi: " + e); }
        Thread t = new Thread(() -> {
            try {
                loaded = load(f, origin);
            } catch (Throwable e) {
                mc.execute(() -> { error("Lỗi đọc schematic: " + e); toggle(); });
            }
        }, "AutoBuild-loader");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void onDeactivate() {
        queue.clear();
        loaded = null;
        wantRot = false;
        try { AutoBuildHelper.stopMoving(); } catch (Throwable ignored) {}
        try { AutoBuildHelper.restoreBaritone(); } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------ xoay camera mượt (mỗi frame)

    @EventHandler
    private void onRender(Render3DEvent event) {
        long now = System.nanoTime();
        double dt = lastFrameNs == 0 ? 0.016 : Math.min(0.1, (now - lastFrameNs) / 1e9);
        lastFrameNs = now;
        if (!wantRot || rotationMode.get() != RotationMode.Smooth || mc.player == null) return;

        float dy = MathHelper.wrapDegrees(wantYaw - mc.player.getYaw());
        float dp = wantPitch - mc.player.getPitch();
        double ease = 1 - Math.exp(-14 * dt);       // giảm tốc khi gần tới đích
        double maxStep = rotationSpeed.get() * dt;
        double minStep = 90 * dt;                    // không bò lết ở cuối
        double sy = step(dy, ease, maxStep, minStep);
        double sp = step(dp, ease, maxStep, minStep);
        if (sy != 0 || sp != 0) mc.player.changeLookDirection(sy / 0.15, sp / 0.15);
    }

    private static double step(double delta, double ease, double maxStep, double minStep) {
        double a = Math.abs(delta);
        if (a < 0.05) return 0;
        double s = Math.min(Math.max(a * ease, Math.min(minStep, a)), maxStep);
        return Math.copySign(Math.min(s, a), delta);
    }

    private boolean onTarget() {
        return Math.abs(MathHelper.wrapDegrees(mc.player.getYaw() - wantYaw)) < 2.5
            && Math.abs(mc.player.getPitch() - wantPitch) < 2.5;
    }

    // ------------------------------------------------------------ tick

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        List<BuildTask> l = loaded;
        if (l != null) {
            loaded = null;
            queue.clear();
            queue.addAll(l);
            sort();
            info("Đã nạp %d block.", queue.size());
        }
        if (queue.isEmpty()) return;
        tick++;
        if (tick % resortTicks.get() == 0) sort();
        long now = System.currentTimeMillis();

        // 1) dọn các block đã xong / bị chiếm chỗ
        for (Iterator<BuildTask> it = queue.iterator(); it.hasNext(); ) {
            BuildTask t = it.next();
            BlockState cur = mc.world.getBlockState(t.pos);
            if (cur.getBlock() == t.state.getBlock()) {
                if (!AutoBuildHelper.matches(cur, t.state)) differing++;
                it.remove();
            } else if (!cur.isReplaceable()) {
                occupied++;
                it.remove();
            }
        }

        // 2) đặt block
        boolean smooth = rotationMode.get() == RotationMode.Smooth;
        boolean doRotate = rotationMode.get() != RotationMode.Off;
        boolean canPlaceNow = now >= nextPlaceAt;
        int placed = 0, plans = 0, noPlan = 0, blocked = 0;
        boolean actionable = false, waiting = false;
        BlockPos firstOut = null, blockedPos = null;
        Set<String> missing = new LinkedHashSet<>();

        for (Iterator<BuildTask> it = queue.iterator(); it.hasNext(); ) {
            BuildTask t = it.next();
            if (!AutoBuildHelper.inReach(t.pos, range.get())) {
                if (firstOut == null) firstOut = t.pos;
                continue;
            }
            if (now < t.retryAt) { actionable = true; continue; } // chờ server xác nhận

            if (!mc.world.canPlace(t.state, t.pos, ShapeContext.of(mc.player))) {
                blocked++;
                blockedPos = t.pos;
                continue;
            }
            int slot = findSlot(t.state.getBlock());
            if (slot < 0) { missing.add(t.state.getBlock().getName().getString()); continue; }

            if (t.plan == null || now > t.planUntil) {
                if (plans >= 8) { actionable = true; continue; } // giới hạn tải mỗi tick
                plans++;
                t.plan = AutoBuildHelper.plan(t.pos, t.state, mc.player.getInventory().getStack(slot), !ignoreOrientation.get());
                t.planUntil = now + (t.plan == null ? 2000 : 1000);
            }
            if (t.plan == null) { noPlan++; continue; }
            actionable = true;
            if (waiting) continue;

            if (smooth && doRotate && !(wantRot && onTargetFor(t.plan))) {
                wantYaw = t.plan.yaw();
                wantPitch = t.plan.pitch();
                wantRot = true;
                if (!onTarget()) { waiting = true; continue; }
            }
            if (!canPlaceNow || placed >= maxPerTick.get()) continue;

            mc.player.getInventory().setSelectedSlot(slot);
            boolean ok = AutoBuildHelper.place(t.plan, doRotate,
                sneakMode.get() == SneakMode.Always, sneakMode.get() == SneakMode.Auto);
            t.attempts++;
            t.retryAt = now + 350;
            t.plan = null; // tính lại nếu phải thử lại
            if (ok) placed++;
            if (t.attempts > 6) { gaveUp++; it.remove(); }
        }

        // 3) di chuyển
        if (actionable) {
            if (AutoBuildHelper.isMoving()) AutoBuildHelper.stopMoving();
        } else {
            wantRot = false;
            if (firstOut != null && autoMove.get()) AutoBuildHelper.moveNear(firstOut, Math.max(1, range.get() - 1));
            else if (blockedPos != null && autoMove.get()) AutoBuildHelper.moveAway(blockedPos);
        }

        // 4) báo kẹt
        if (!actionable && firstOut == null && now - lastReport > 10000) {
            lastReport = now;
            info("Còn %d block chưa đặt được. Thiếu item: %s | không có điểm tựa/hướng: %d | bị chặn: %d",
                queue.size(), missing.isEmpty() ? "không" : String.join(", ", missing), noPlan, blocked);
        }

        if (placed > 0) {
            double r = Math.pow(random.nextDouble(), curvePower.get());
            int lo = Math.min(minDelayMs.get(), maxDelayMs.get());
            int hi = Math.max(minDelayMs.get(), maxDelayMs.get());
            nextPlaceAt = now + lo + (long) ((hi - lo) * r);
        }

        if (queue.isEmpty()) {
            info("Xong. Khác trạng thái: %d, bị chiếm chỗ: %d, bỏ qua: %d.", differing, occupied, gaveUp);
            toggle();
        }
    }

    private boolean onTargetFor(AutoBuildHelper.Plan p) {
        return Math.abs(MathHelper.wrapDegrees(wantYaw - p.yaw())) < 2.5 && Math.abs(wantPitch - p.pitch()) < 2.5;
    }

    /** Thấp -> cao (nền trước, mái sau), cùng tầng thì gần người trước. */
    private void sort() {
        BlockPos p = mc.player.getBlockPos();
        queue.sort(Comparator.comparingInt((BuildTask t) -> t.pos.getY())
            .thenComparingDouble(t -> t.pos.getSquaredDistance(p)));
    }

    private int findSlot(Block block) {
        Item item = block.asItem();
        var inv = mc.player.getInventory();
        for (int i = 0; i < 9; i++) if (inv.getStack(i).isOf(item)) return i;
        return -1;
    }

    // ------------------------------------------------------------ đọc schematic

    private static List<BuildTask> load(File f, BlockPos origin) throws Exception {
        NbtCompound root = NbtIo.readCompressed(f.toPath(), NbtSizeTracker.ofUnlimitedBytes());
        String n = f.getName().toLowerCase(Locale.ROOT);
        if (n.endsWith(".schem")) return readSponge(root, origin);
        if (n.endsWith(".nbt")) return readStructure(root, origin);
        throw new IllegalArgumentException("Chỉ hỗ trợ .schem và .nbt");
    }

    private static List<BuildTask> readSponge(NbtCompound root, BlockPos origin) {
        NbtCompound s = root.contains("Schematic") ? root.getCompoundOrEmpty("Schematic") : root;
        int w = s.getInt("Width", 0), h = s.getInt("Height", 0), l = s.getInt("Length", 0);
        NbtCompound palNbt;
        byte[] data;
        if (s.contains("Blocks")) { // Sponge v3
            NbtCompound b = s.getCompoundOrEmpty("Blocks");
            palNbt = b.getCompoundOrEmpty("Palette");
            data = b.getByteArray("Data").orElse(new byte[0]);
        } else { // Sponge v2
            palNbt = s.getCompoundOrEmpty("Palette");
            data = s.getByteArray("BlockData").orElse(new byte[0]);
        }
        Map<Integer, BlockState> palette = new HashMap<>();
        for (String key : palNbt.getKeys()) {
            BlockState st = parseState(key);
            if (st != null) palette.put(palNbt.getInt(key, 0), st);
        }
        List<BuildTask> out = new ArrayList<>();
        int idx = 0, i = 0;
        while (i < data.length) {
            int v = 0, shift = 0, b;
            do {
                b = data[i++];
                v |= (b & 0x7F) << shift;
                shift += 7;
            } while ((b & 0x80) != 0 && i < data.length);
            int x = idx % w, z = (idx / w) % l, y = idx / (w * l);
            idx++;
            BlockState st = palette.get(v);
            if (st == null || st.isAir()) continue;
            out.add(new BuildTask(origin.add(x, y, z), st));
        }
        return out;
    }

    private static List<BuildTask> readStructure(NbtCompound root, BlockPos origin) {
        NbtList pal = root.getListOrEmpty("palette");
        List<BlockState> states = new ArrayList<>();
        for (int i = 0; i < pal.size(); i++) {
            NbtCompound e = pal.getCompoundOrEmpty(i);
            StringBuilder sb = new StringBuilder(e.getString("Name", "minecraft:air"));
            NbtCompound props = e.getCompoundOrEmpty("Properties");
            if (!props.getKeys().isEmpty()) {
                sb.append('[');
                boolean first = true;
                for (String k : props.getKeys()) {
                    if (!first) sb.append(',');
                    sb.append(k).append('=').append(props.getString(k, ""));
                    first = false;
                }
                sb.append(']');
            }
            states.add(parseState(sb.toString()));
        }
        List<BuildTask> out = new ArrayList<>();
        NbtList blocks = root.getListOrEmpty("blocks");
        for (int i = 0; i < blocks.size(); i++) {
            NbtCompound b = blocks.getCompoundOrEmpty(i);
            NbtList p = b.getListOrEmpty("pos");
            int si = b.getInt("state", -1);
            if (si < 0 || si >= states.size()) continue;
            BlockState st = states.get(si);
            if (st == null || st.isAir()) continue;
            out.add(new BuildTask(origin.add(p.getInt(0, 0), p.getInt(1, 0), p.getInt(2, 0)), st));
        }
        return out;
    }

    /** "minecraft:oak_stairs[facing=north,half=bottom]" -> BlockState */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState parseState(String s) {
        String name = s, props = "";
        int br = s.indexOf('[');
        if (br >= 0) { name = s.substring(0, br); props = s.substring(br + 1, s.length() - 1); }
        Identifier id = Identifier.tryParse(name);
        if (id == null) return null;
        Block block = Registries.BLOCK.get(id);
        BlockState st = block.getDefaultState();
        if (!props.isEmpty()) {
            for (String kv : props.split(",")) {
                String[] a = kv.split("=");
                if (a.length != 2) continue;
                Property p = block.getStateManager().getProperty(a[0]);
                if (p == null) continue;
                Optional o = p.parse(a[1]);
                if (o.isPresent()) st = st.with(p, (Comparable) o.get());
            }
        }
        return st;
    }
}
