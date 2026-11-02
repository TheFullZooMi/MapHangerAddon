package com.example.addon.modules; // поменяй на package своего аддона, если он другой

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;

/**
 * MapHanger: ставит рамки на стену рядом с тобой и сразу вставляет в пустые рамки карты из инвентаря.
 * Рамки должны лежать в хотбаре. Карты могут быть где угодно в инвентаре.
 */
public class MapHanger extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgFrames = settings.createGroup("Frames");

    private final Setting<Boolean> fill = sgGeneral.add(new BoolSetting.Builder()
        .name("fill-frames")
        .description("Вставлять карты в пустые рамки рядом.")
        .defaultValue(true)
        .build());

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("Радиус действия.")
        .defaultValue(4.0)
        .min(1.0)
        .sliderMax(5.0)
        .build());

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Пауза между действиями в тиках.")
        .defaultValue(3)
        .min(0)
        .sliderMax(20)
        .build());

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Поворачиваться к цели перед действием.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> mapSlot = sgGeneral.add(new IntSetting.Builder()
        .name("map-hotbar-slot")
        .description("Слот хотбара (1-9), в который подтягиваются карты из инвентаря. Держи его пустым или под карты.")
        .defaultValue(9)
        .range(1, 9)
        .sliderRange(1, 9)
        .build());

    private final Setting<Boolean> place = sgFrames.add(new BoolSetting.Builder()
        .name("place-frames")
        .description("Самому ставить рамки на стену.")
        .defaultValue(true)
        .build());

    private final Setting<Direction> wallSide = sgFrames.add(new EnumSetting.Builder<Direction>()
        .name("frame-faces")
        .description("Куда смотрит рамка. Если стена справа от тебя на востоке, то рамка смотрит на WEST.")
        .defaultValue(Direction.WEST)
        .build());

    private final Setting<Integer> minY = sgFrames.add(new IntSetting.Builder()
        .name("min-y-offset")
        .description("Нижняя граница по высоте относительно твоих ног.")
        .defaultValue(0)
        .range(-5, 5)
        .sliderRange(-5, 5)
        .build());

    private final Setting<Integer> maxY = sgFrames.add(new IntSetting.Builder()
        .name("max-y-offset")
        .description("Верхняя граница по высоте относительно твоих ног.")
        .defaultValue(2)
        .range(-5, 5)
        .sliderRange(-5, 5)
        .build());

    private final Map<Integer, Integer> filled = new HashMap<>();
    private final Map<BlockPos, Integer> placed = new HashMap<>();
    private int tick;
    private int cooldown;

    public MapHanger() {
        super(Categories.World, "map-hanger", "Ставит рамки и сразу вешает в них карты.");
    }

    @Override
    public void onActivate() {
        tick = 0;
        cooldown = 0;
        filled.clear();
        placed.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        tick++;
        filled.values().removeIf(t -> tick - t > 30);
        placed.values().removeIf(t -> tick - t > 30);

        if (cooldown > 0) {
            cooldown--;
            return;
        }

        if (fill.get() && tryFill()) {
            cooldown = delay.get();
            return;
        }

        if (place.get() && tryPlace()) {
            cooldown = delay.get();
        }
    }

    private boolean tryFill() {
        ItemFrameEntity target = null;
        double best = range.get() * range.get();

        for (Entity e : mc.world.getEntities()) {
            if (!(e instanceof ItemFrameEntity frame)) continue;
            if (!frame.getHeldItemStack().isEmpty()) continue;
            if (filled.containsKey(frame.getId())) continue;

            double d = mc.player.squaredDistanceTo(frame);
            if (d <= best) {
                best = d;
                target = frame;
            }
        }

        if (target == null) return false;

        FindItemResult hotbarMap = InvUtils.findInHotbar(Items.FILLED_MAP);
        if (!hotbarMap.found()) {
            // Карты нет в хотбаре: подтягиваем первую из инвентаря, вешать будем на следующем действии.
            FindItemResult invMap = InvUtils.find(Items.FILLED_MAP);
            if (!invMap.found()) return false;
            InvUtils.move().from(invMap.slot()).toHotbar(mapSlot.get() - 1);
            return true;
        }

        InvUtils.swap(hotbarMap.slot(), false);

        final ItemFrameEntity frame = target;
        filled.put(frame.getId(), tick);

        Runnable action = () -> {
            mc.interactionManager.interactEntity(mc.player, frame, Hand.MAIN_HAND);
            mc.player.swingHand(Hand.MAIN_HAND);
        };

        if (rotate.get()) {
            Vec3d c = frame.getBoundingBox().getCenter();
            Rotations.rotate(Rotations.getYaw(c), Rotations.getPitch(c), action);
        } else {
            action.run();
        }
        return true;
    }

    private boolean tryPlace() {
        FindItemResult frameItem = InvUtils.findInHotbar(Items.ITEM_FRAME, Items.GLOW_ITEM_FRAME);
        if (!frameItem.found()) return false;

        Direction face = wallSide.get();
        BlockPos origin = mc.player.getBlockPos();
        Vec3d eye = mc.player.getEyePos();
        int r = (int) Math.ceil(range.get());
        double maxSq = range.get() * range.get();

        BlockPos bestWall = null;
        Vec3d bestHit = null;
        BlockPos bestFramePos = null;
        double best = Double.MAX_VALUE;

        for (int dx = -r; dx <= r; dx++) {
            for (int dy = Math.max(-r, minY.get()); dy <= Math.min(r, maxY.get()); dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos wall = origin.add(dx, dy, dz);
                    BlockPos framePos = wall.offset(face);

                    if (placed.containsKey(framePos)) continue;
                    if (!mc.world.getBlockState(wall).isSideSolidFullSquare(mc.world, wall, face)) continue;
                    if (!mc.world.getBlockState(framePos).isAir()) continue;
                    if (!mc.world.getEntitiesByClass(ItemFrameEntity.class, new Box(framePos), e -> true).isEmpty()) continue;

                    Vec3d hit = Vec3d.ofCenter(wall).add(face.getOffsetX() * 0.5, face.getOffsetY() * 0.5, face.getOffsetZ() * 0.5);
                    double d = eye.squaredDistanceTo(hit);
                    if (d > maxSq || d >= best) continue;

                    best = d;
                    bestWall = wall;
                    bestHit = hit;
                    bestFramePos = framePos;
                }
            }
        }

        if (bestWall == null) return false;

        InvUtils.swap(frameItem.slot(), false);

        final BlockPos wall = bestWall;
        final Vec3d hitVec = bestHit;
        placed.put(bestFramePos, tick);

        Runnable action = () -> {
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, new BlockHitResult(hitVec, face, wall, false));
            mc.player.swingHand(Hand.MAIN_HAND);
        };

        if (rotate.get()) {
            Rotations.rotate(Rotations.getYaw(hitVec), Rotations.getPitch(hitVec), action);
        } else {
            action.run();
        }
        return true;
    }
}
