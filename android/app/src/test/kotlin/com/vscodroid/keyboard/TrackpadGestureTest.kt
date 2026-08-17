package com.vscodroid.keyboard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [TrackpadGesture] and [TrackpadGear].
 *
 * The trackpad is the only arrow-key affordance the extra key row has, so an
 * accumulator that stops paying out, or a gear that never engages, takes cursor
 * movement away from every touch user. None of that is reachable from the View,
 * which needs MotionEvents and a display; it is all reachable from here.
 */
class TrackpadGestureTest {

    /**
     * Moves the finger back and forth in 5dp steps, which is below the smallest
     * step any gear asks for (FAST wants 6dp), so path length builds up without
     * a single arrow being emitted and without leaving anything pending.
     * Each call adds 10dp of travel.
     */
    private fun driveDp(gesture: TrackpadGesture, dp: Float, density: Float) {
        val steps = (dp / 10f).toInt()
        repeat(steps) {
            gesture.accumulate(5f * density, 0f, density)
            gesture.accumulate(-5f * density, 0f, density)
        }
    }

    @Nested
    inner class GearThresholds {

        @Test
        fun `a drag starts in the precise gear`() {
            assertEquals(TrackpadGear.PRECISE, TrackpadGear.forTotalDp(0f))
        }

        @Test
        fun `moderate engages at exactly 100dp of travel, not before`() {
            assertEquals(TrackpadGear.PRECISE, TrackpadGear.forTotalDp(99.9f))
            assertEquals(TrackpadGear.MODERATE, TrackpadGear.forTotalDp(100f))
        }

        @Test
        fun `fast engages at exactly 250dp of travel, not before`() {
            assertEquals(TrackpadGear.MODERATE, TrackpadGear.forTotalDp(249.9f))
            assertEquals(TrackpadGear.FAST, TrackpadGear.forTotalDp(250f))
            assertEquals(TrackpadGear.FAST, TrackpadGear.forTotalDp(5000f))
        }

        @Test
        fun `each gear asks for a shorter step than the one before it`() {
            // The whole point of the gears: the further the finger has already
            // travelled, the fewer pixels each arrow costs. Equal steps would
            // leave three gears that behave as one.
            assertTrue(
                TrackpadGear.PRECISE.thresholdDp > TrackpadGear.MODERATE.thresholdDp,
                "precise should cost more travel per arrow than moderate"
            )
            assertTrue(
                TrackpadGear.MODERATE.thresholdDp > TrackpadGear.FAST.thresholdDp,
                "moderate should cost more travel per arrow than fast"
            )
        }

        @Test
        fun `the gear is read from travel so far, at this screen density`() {
            val gesture = TrackpadGesture()
            assertEquals(TrackpadGear.PRECISE, gesture.gear(2f))

            driveDp(gesture, 100f, 2f)
            assertEquals(200f, gesture.totalDistancePx, "100dp at density 2 is 200px")
            assertEquals(TrackpadGear.MODERATE, gesture.gear(2f))

            driveDp(gesture, 150f, 2f)
            assertEquals(TrackpadGear.FAST, gesture.gear(2f))
        }

        @Test
        fun `a shorter step in a higher gear turns the same delta into an arrow`() {
            val density = 2f
            val fourteenDp = 14f * density

            val cold = TrackpadGesture()
            assertEquals(
                emptyList<String>(), cold.accumulate(fourteenDp, 0f, density),
                "14dp is under the precise gear's 24dp step"
            )

            val warm = TrackpadGesture()
            driveDp(warm, 100f, density)
            assertEquals(
                listOf("ArrowRight"), warm.accumulate(fourteenDp, 0f, density),
                "14dp is exactly the moderate gear's step"
            )
        }
    }

    @Nested
    inner class DirectionSelection {

        private val density = 1f
        private val step = TrackpadGear.PRECISE.thresholdDp // 24px at density 1

        @Test
        fun `dragging right emits ArrowRight`() {
            assertEquals(listOf("ArrowRight"), TrackpadGesture().accumulate(step, 0f, density))
        }

        @Test
        fun `dragging left emits ArrowLeft`() {
            assertEquals(listOf("ArrowLeft"), TrackpadGesture().accumulate(-step, 0f, density))
        }

        @Test
        fun `dragging down emits ArrowDown`() {
            // Screen coordinates grow downward, so a positive dy is ArrowDown.
            // Inverting this pair is the regression that makes the trackpad feel
            // broken while still emitting arrows.
            assertEquals(listOf("ArrowDown"), TrackpadGesture().accumulate(0f, step, density))
        }

        @Test
        fun `dragging up emits ArrowUp`() {
            assertEquals(listOf("ArrowUp"), TrackpadGesture().accumulate(0f, -step, density))
        }

        @Test
        fun `the emitted names are the ones the injector knows`() {
            // KeyMapping is what turns these strings into key events; a rename on
            // one side and not the other emits arrows that inject nothing.
            for (direction in listOf("ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown")) {
                assertTrue(
                    KeyMapping.getKeyDef(direction) != null,
                    "$direction is emitted by the trackpad but unknown to KeyMapping"
                )
            }
        }
    }

    @Nested
    inner class Accumulation {

        @Test
        fun `travel under the step emits nothing but is kept`() {
            val gesture = TrackpadGesture()
            assertEquals(emptyList<String>(), gesture.accumulate(20f, 0f, 1f))
            assertEquals(20f, gesture.pendingDxPx)
            // Discarding the leftover would make a slow drag emit nothing at all.
            assertEquals(listOf("ArrowRight"), gesture.accumulate(4f, 0f, 1f))
        }

        @Test
        fun `one large delta pays out every whole step in it`() {
            val gesture = TrackpadGesture()
            // 70px at density 1, precise gear: two arrows, 22px still owed.
            assertEquals(listOf("ArrowRight", "ArrowRight"), gesture.accumulate(70f, 0f, 1f))
            assertEquals(22f, gesture.pendingDxPx, "the remainder carries into the next delta")
        }

        @Test
        fun `the remainder carries rather than resetting to zero`() {
            val gesture = TrackpadGesture()
            gesture.accumulate(30f, 0f, 1f) // one arrow, 6px owed
            assertEquals(6f, gesture.pendingDxPx)
            // Zeroing instead of subtracting would need 24 more px here, not 18.
            assertEquals(listOf("ArrowRight"), gesture.accumulate(18f, 0f, 1f))
        }

        @Test
        fun `reversing direction spends the leftover rather than compounding it`() {
            val gesture = TrackpadGesture()
            gesture.accumulate(20f, 0f, 1f)
            assertEquals(emptyList<String>(), gesture.accumulate(-20f, 0f, 1f))
            assertEquals(0f, gesture.pendingDxPx)
            // Path length still grew, even though the finger came back.
            assertEquals(40f, gesture.totalDistancePx)
        }
    }

    @Nested
    inner class Diagonal {

        @Test
        fun `a diagonal drag emits both axes, horizontal first`() {
            val gesture = TrackpadGesture()
            assertEquals(
                listOf("ArrowRight", "ArrowUp"),
                gesture.accumulate(30f, -30f, 1f),
                "a diagonal drag must move the cursor on both axes"
            )
            assertEquals(6f, gesture.pendingDxPx)
            assertEquals(-6f, gesture.pendingDyPx)
        }

        @Test
        fun `an axis under the step waits while the other one pays out`() {
            val gesture = TrackpadGesture()
            assertEquals(listOf("ArrowRight"), gesture.accumulate(30f, 10f, 1f))
            assertEquals(10f, gesture.pendingDyPx, "the vertical travel is owed, not dropped")
        }

        @Test
        fun `both axes can pay out more than once in a single delta`() {
            val gesture = TrackpadGesture()
            // 60px each way at density 1: the 100dp of path puts this in the
            // moderate gear (14px step), so four arrows on each axis.
            val directions = gesture.accumulate(60f, 60f, 1f)
            assertEquals(TrackpadGear.MODERATE, gesture.gear(1f))
            assertEquals(4, directions.count { it == "ArrowRight" })
            assertEquals(4, directions.count { it == "ArrowDown" })
        }
    }

    @Nested
    inner class DragBoundary {

        @Test
        fun `reset clears travel, leftovers and the gear`() {
            val gesture = TrackpadGesture()
            driveDp(gesture, 300f, 1f)
            gesture.accumulate(10f, 10f, 1f)
            assertEquals(TrackpadGear.FAST, gesture.gear(1f))

            gesture.reset()

            assertEquals(0f, gesture.totalDistancePx)
            assertEquals(0f, gesture.pendingDxPx)
            assertEquals(0f, gesture.pendingDyPx)
            assertEquals(TrackpadGear.PRECISE, gesture.gear(1f), "a new drag starts precise")
        }

        @Test
        fun `leftover travel does not survive into the next drag`() {
            val gesture = TrackpadGesture()
            assertEquals(emptyList<String>(), gesture.accumulate(20f, 0f, 1f))
            gesture.reset()
            // Carrying the 20px across the lift would emit an arrow the user did
            // not ask for, from a finger that has only just touched down.
            assertEquals(emptyList<String>(), gesture.accumulate(20f, 0f, 1f))
        }
    }
}
