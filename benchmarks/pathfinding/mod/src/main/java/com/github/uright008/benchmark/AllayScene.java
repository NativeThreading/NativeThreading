package com.github.uright008.benchmark;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.pathfinder.Path;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Random;

/** Owns one live fixture, accessed only on its server thread; never a worker snapshot. */
public final class AllayScene {
    // Instrumentation routing, not a reusable world cache. Match world AND entity identity;
    // no worker readers/writers, no join needed, clear on server stop.
    private static AllayScene current;
    final ServerLevel level;
    final Allay[] mobs;
    final long seed;
    final AllayDropLedger ledger = new AllayDropLedger();
    private final IdentityHashMap<Mob, Boolean> members = new IdentityHashMap<>();
    private final ArrayList<ItemEntity> items = new ArrayList<>();
    AllayRunStage run;
    long clock;
    long lastPhaseEntityTicks = -1;

    AllayScene(ServerLevel level, int entities, long seed) {
        AllayScenarioHelper.validateEntities(entities);
        if (!level.getGameRules().get(GameRules.MOB_GRIEFING)) {
            throw new IllegalArgumentException("Allay benchmark requires mob_griefing=true");
        }
        this.level = level;
        this.seed = seed;
        // Allay required path length 48 + navigation radius offset 8 = 56 blocks.
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                level.getChunk(x, z);
                level.setChunkForced(x, z, true);
            }
        }
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 64; y <= 128; y++) {
                    boolean solid = x == 0 || x == 15 || z == 0 || z == 15 || (y - 64) % 8 == 0;
                    level.setBlock(new BlockPos(x, y, z), (solid ? Blocks.STONE : Blocks.AIR).defaultBlockState(), 2);
                }
            }
        }
        mobs = new Allay[entities];
        Random random = new Random(seed);
        for (int i = 0; i < entities; i++) {
            Allay mob = new Allay(EntityTypes.ALLAY, level);
            mob.setPos(2 + random.nextDouble() * 12, 64 + (i % 8) * 8 + 2 + random.nextDouble() * 4,
                2 + random.nextDouble() * 12);
            mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND, 1));
            mob.setPersistenceRequired();
            if (level.getBlockCollisions(mob, mob.getBoundingBox()).iterator().hasNext()) {
                throw new IllegalStateException("Allay spawned inside a wall/platform");
            }
            if (!level.addFreshEntity(mob)) throw new IllegalStateException("Allay spawn rejected");
            mobs[i] = mob;
            members.put(mob, true);
        }
        current = this;
        level.getServer().tickRateManager().setFrozen(true);
    }

    void startTick() {
        // RCON/profile setup may take arbitrary wall time. Freeze game simulation
        // between phases; do not count the transition tick while vanilla is still frozen.
        if (run == null) return;
        if (level.getServer().tickRateManager().isFrozen()) level.getServer().tickRateManager().setFrozen(false);
        if (!level.getServer().tickRateManager().runsNormally()) return;
        clock++;
        long start = System.nanoTime();
        if (run != null) run.startTick(start);
        for (Allay mob : mobs) {
            ItemStack stack = mob.getInventory().getItem(0);
            if (!stack.isEmpty() && stack.getCount() > 1) {
                if (run != null) {
                    run.inventoryResets++;
                    run.inventoryItemsRemoved += stack.getCount() - 1;
                }
                stack.setCount(1);
            }
        }
        if (run != null) {
            run.dropItems();
            run.maintenanceNanos += System.nanoTime() - start;
        }
    }

    void endTick() {
        if (run == null || !run.inTick()) return;
        long start = System.nanoTime();
        items.removeIf(item -> {
            if (!item.isRemoved()) return false;
            ledger.lost(item.getUUID()); // Already picked/merged entries were removed by hooks.
            return true;
        });
        if (run != null) {
            run.maintenanceNanos += System.nanoTime() - start;
            if (run.endTick()) {
                run = null;
                level.getServer().tickRateManager().setFrozen(true);
            }
        }
    }

    void spawn(AllayScenarioHelper.Position pos, AllayRunStage phase) {
        // Explicit zero launch velocity avoids an extra random impulse; gravity stays vanilla.
        ItemEntity item = new ItemEntity(level, pos.x(), pos.y(), pos.z(), new ItemStack(Items.DIAMOND), 0, 0, 0);
        item.setNoPickUpDelay();
        if (!level.addFreshEntity(item)) throw new IllegalStateException("Diamond spawn rejected");
        var drop = new AllayDropLedger.Drop(phase.phase, pos.layer(), clock, System.nanoTime());
        ledger.add(item.getUUID(), drop);
        phase.drops.add(drop);
        items.add(item);
    }

    private static AllayScene forEntity(Entity entity) {
        AllayScene scene = current;
        if (scene == null || entity.level() != scene.level) return null;
        if (!scene.level.getServer().isSameThread()) {
            throw new IllegalStateException("Allay instrumentation requires main-thread callbacks");
        }
        return scene;
    }

    public static long searchStart(Mob mob) {
        AllayScene scene = forEntity(mob);
        return scene != null && scene.members.containsKey(mob) && scene.run != null && scene.run.inTick()
            ? System.nanoTime() : 0;
    }

    public static void searchEnd(Mob mob, Path path, long started) {
        if (started == 0) return;
        long elapsed = System.nanoTime() - started;
        AllayScene scene = forEntity(mob);
        if (scene == null || scene.run == null) throw new IllegalStateException("Search crossed measurement boundary");
        scene.run.searchFinished(path, elapsed);
    }

    public static void pickup(Allay mob, ItemEntity item) {
        AllayScene scene = forEntity(mob);
        if (scene == null || !scene.members.containsKey(mob)) return;
        var picked = scene.ledger.pickup(item.getUUID(), item.isRemoved() ? 0 : item.getItem().getCount());
        if (scene.run != null && scene.run.inTick()) scene.run.picked(picked);
    }

    public static void merged(ItemEntity target, ItemEntity source) {
        AllayScene scene = forEntity(target);
        if (scene == null) return;
        boolean tracked = scene.ledger.merge(target.getUUID(), source.getUUID(), target.getItem().getCount());
        if (tracked && scene.run != null && scene.run.inTick()) scene.run.merges++;
    }

    public static void clear() {
        current = null;
    }
}
