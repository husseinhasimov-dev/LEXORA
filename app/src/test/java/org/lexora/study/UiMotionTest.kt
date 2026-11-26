package org.lexora.study

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import org.junit.Assert.*
import org.junit.Test

class UiMotionTest {
    @Test fun motionDurationsStaySubtleAndFast() {
        assertTrue(MOTION_FAST in 150..250)
        assertTrue(MOTION_STANDARD in 150..250)
        assertTrue(MOTION_PROGRESS in 150..250)
        assertTrue(MOTION_PRESS in 80..150)
    }

    @Test fun reducedMotionDisablesTransitions() {
        val peer = peerScreenTransition(reducedMotion = true)
        assertEquals(EnterTransition.None, peer.targetContentEnter)
        assertEquals(ExitTransition.None, peer.initialContentExit)

        val drillForward = drillTransition(forward = true, reducedMotion = true)
        assertEquals(EnterTransition.None, drillForward.targetContentEnter)
        assertEquals(ExitTransition.None, drillForward.initialContentExit)

        val drillBack = drillTransition(forward = false, reducedMotion = true)
        assertEquals(EnterTransition.None, drillBack.targetContentEnter)
        assertEquals(ExitTransition.None, drillBack.initialContentExit)

        val step = stepTransition(reducedMotion = true)
        assertEquals(EnterTransition.None, step.targetContentEnter)
        assertEquals(ExitTransition.None, step.initialContentExit)

        assertEquals(EnterTransition.None, panelEnter(reducedMotion = true))
        assertEquals(ExitTransition.None, panelExit(reducedMotion = true))
    }

    @Test fun standardMotionProducesActiveTransitions() {
        val peer = peerScreenTransition(reducedMotion = false)
        assertNotEquals(EnterTransition.None, peer.targetContentEnter)
        assertNotEquals(ExitTransition.None, peer.initialContentExit)

        val drill = drillTransition(forward = true, reducedMotion = false)
        assertNotEquals(EnterTransition.None, drill.targetContentEnter)
        assertNotEquals(ExitTransition.None, drill.initialContentExit)

        val step = stepTransition(reducedMotion = false)
        assertNotEquals(EnterTransition.None, step.targetContentEnter)
        assertNotEquals(ExitTransition.None, step.initialContentExit)

        assertNotEquals(EnterTransition.None, panelEnter(reducedMotion = false))
        assertNotEquals(ExitTransition.None, panelExit(reducedMotion = false))
    }
}
