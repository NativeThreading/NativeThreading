package com.github.uright008.ep;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/** Captures a blast's shadow state so the pipeline stages
 *  ({@link ExplosionBlockStage}, {@link ExplosionEntityStage},
 *  {@link ExplosionChunkGridCache}) can run without touching the
 *  ServerExplosion mixin's shadow fields. Built by the thin mixin on the main
 *  thread at each injection point; stages are pure functions of this context. */
public record ExplosionContext(
        ServerLevel level,
        Vec3 center,
        float radius,
        @Nullable Entity source,
        DamageSource damageSource,
        ExplosionDamageCalculator damageCalculator,
        Map<Player, Vec3> hitPlayers,
        boolean fire,
        boolean interactsWithBlocks,
        ServerExplosion self) {

    /** True when vanilla's block stage (interactWithBlocks / createFire)
     *  consumes nothing — KEEP block interaction and no fire. In that case the
     *  exploded-position list has no consumer and the ray/flat-view pipeline
     *  can be short-circuited. */
    public boolean blockStageIsSkipped() {
        return !this.interactsWithBlocks && !this.fire;
    }
}
