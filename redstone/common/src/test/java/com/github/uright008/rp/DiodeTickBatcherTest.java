package com.github.uright008.rp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DiodeTickBatcherTest {

    @Test
    void lockedDiodeProducesNoAction() {
        assertEquals(DiodeTickBatcher.Transition.NONE,
                DiodeTickBatcher.decideTransition(true, false, false));
    }

    @Test
    void poweredDiodeWithoutInputPowersOff() {
        assertEquals(DiodeTickBatcher.Transition.POWER_OFF,
                DiodeTickBatcher.decideTransition(false, true, false));
    }

    @Test
    void unpoweredDiodeWithoutInputPowersOnAndReschedules() {
        assertEquals(DiodeTickBatcher.Transition.POWER_ON_AND_RESCHEDULE,
                DiodeTickBatcher.decideTransition(false, false, false));
    }

    @Test
    void unpoweredDiodeWithInputPowersOn() {
        assertEquals(DiodeTickBatcher.Transition.POWER_ON,
                DiodeTickBatcher.decideTransition(false, false, true));
    }

    @Test
    void poweredDiodeWithInputProducesNoAction() {
        assertEquals(DiodeTickBatcher.Transition.NONE,
                DiodeTickBatcher.decideTransition(false, true, true));
    }
}
