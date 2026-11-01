package com.github.uright008.nt.explosion;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExplosionStatsTest {

    @AfterEach
    void cleanUp() {
        ExplosionStats.reset();
    }

    @Test
    void recordsCountersAndTimings() {
        ExplosionStats.recordBlockStage(1_000L, 2_000L);
        ExplosionStats.recordBlockSerialFallback();
        ExplosionStats.recordEntityStage(10_000L, 20_000L, 30_000L, 100, 40, 30, 5, 70L);
        ExplosionStats.recordEntitySerialFallback();
        ExplosionStats.recordClearRegionHit();

        String joined = String.join("\n", ExplosionStats.lines());
        assertThat(joined).contains("blasts: block=1 entity=1");
        assertThat(joined).contains("serial fallbacks: block=1 entity=1");
        assertThat(joined).contains("total=100 (live=40, flat=60)");
        assertThat(joined).contains("dedup hits=30 (75.0% of live)");
        assertThat(joined).contains("grouped=70 (70.0% of victims)");
        assertThat(joined).contains("units: 5 (5.00 per entity blast)");
        assertThat(joined).contains("clear-region fast path=1 hits");
        assertThat(joined).contains("block capture=1.0us worker=2.0us");
        assertThat(joined).contains("entity capture=10.0us worker=20.0us apply=30.0us");
        assertThat(joined).contains("ChunkGrid cache:");
        assertThat(joined).contains("last blast:");
    }

    @Test
    void resetClearsCounters() {
        ExplosionStats.recordBlockStage(1_000L, 2_000L);
        ExplosionStats.recordEntityStage(10_000L, 20_000L, 30_000L, 100, 40, 30, 5, 70L);
        ExplosionStats.reset();

        String joined = String.join("\n", ExplosionStats.lines());
        assertThat(joined).contains("blasts: block=0 entity=0");
        assertThat(joined).contains("total=0 (live=0, flat=0)");
        assertThat(joined).contains("block capture=0.0us worker=0.0us");
    }

    @Test
    void paramsLineReportsEffectiveTuning() {
        String params = ExplosionStats.paramsLine();
        assertThat(params).contains("dedupSlots=" + ExplosionEntityStage.exposureDedupCapacity());
        assertThat(params).contains("groupSlots=" + ExplosionEntityStage.groupCapacity());
        assertThat(params).contains("clearRegionCells=" + ExplosionExposureComputer.clearRegionMaxCells());
        assertThat(params).contains("rays=" + ExplosionRayParams.RAY_PARAMS.size());
        assertThat(params).contains("pool=");
    }
}
