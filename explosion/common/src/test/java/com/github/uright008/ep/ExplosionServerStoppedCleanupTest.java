package com.github.uright008.ep;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The same jar runs in a client's integrated server, where each single-player session
 * creates and discards a fresh set of {@code ServerLevel}s. The module's cross-blast
 * statics must therefore be droppable when that server stops; this pins the cleanup
 * contract without needing a live world.
 */
class ExplosionServerStoppedCleanupTest {

    @Test
    void onServerStopped_isIdempotentAndDropsCachedState() {
        assertThatCode(() -> {
            ExplosionParallelization.onServerStopped();
            ExplosionParallelization.onServerStopped();
        }).doesNotThrowAnyException();

        assertThat(ExplosionChunkGridCache.hits()).isZero();
        assertThat(ExplosionChunkGridCache.misses()).isZero();
        assertThat(ExplosionVisibilitySnapshot.active()).isFalse();
    }

    @Test
    void chunkGridCacheClear_resetsDiagnostics() {
        ExplosionChunkGridCache.clear();
        assertThat(ExplosionChunkGridCache.missRate()).isZero();
        assertThat(ExplosionChunkGridCache.statsLine()).contains("hits=0", "misses=0");
    }
}
