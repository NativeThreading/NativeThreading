package com.github.uright008.hp;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Hopper planning snapshot selection")
class HopperPlanningSnapshotTest {

    private static final BlockPos HOPPER = new BlockPos(10, 64, 20);
    private static final BlockPos TARGET = HOPPER.east();
    private static final BlockPos SOURCE = HOPPER.above();

    @Test
    @DisplayName("selectPlan: prefers a prevalidated push over pull")
    void selectPlan_prefersPush() {
        HopperTransferPlan push = HopperTransferPlan.push(HOPPER, 1, ItemStack.EMPTY, TARGET, Direction.EAST);
        HopperTransferPlan pull = HopperTransferPlan.pullFromContainer(HOPPER, SOURCE, 2, ItemStack.EMPTY);

        HopperParallelHelper.PlanResult result = HopperParallelHelper.selectPlan(
                new HopperParallelHelper.HopperPlanningSnapshot(HOPPER, 0, push, pull));

        assertThat(result.plan()).isSameAs(push);
        assertThat(result.hopperPos()).isEqualTo(HOPPER);
        assertThat(result.cooldownSnapshot()).isZero();
    }

    @Test
    @DisplayName("selectPlan: uses a prevalidated pull when no push was captured")
    void selectPlan_usesPullWhenPushIsAbsent() {
        HopperTransferPlan pull = HopperTransferPlan.pullFromContainer(HOPPER, SOURCE, 2, ItemStack.EMPTY);

        HopperParallelHelper.PlanResult result = HopperParallelHelper.selectPlan(
                new HopperParallelHelper.HopperPlanningSnapshot(HOPPER, 0, null, pull));

        assertThat(result.plan()).isSameAs(pull);
    }

    @Test
    @DisplayName("selectPlan: suppresses transfers while a captured cooldown remains")
    void selectPlan_suppressesPlanForActiveCooldown() {
        HopperTransferPlan push = HopperTransferPlan.push(HOPPER, 1, ItemStack.EMPTY, TARGET, Direction.EAST);

        HopperParallelHelper.PlanResult result = HopperParallelHelper.selectPlan(
                new HopperParallelHelper.HopperPlanningSnapshot(HOPPER, 2, push, null));

        assertThat(result.plan()).isNull();
        assertThat(result.cooldownSnapshot()).isEqualTo(2);
    }
}
