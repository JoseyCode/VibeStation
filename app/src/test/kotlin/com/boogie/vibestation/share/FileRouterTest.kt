package com.boogie.vibestation.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileRouterTest {
    private val router = FileRouter()

    private fun received(events: List<FileEvent>) = events.filterIsInstance<FileEvent.Received>()

    @Test
    fun deliversOnlyWhenHeaderSuccessAndCopyAllHappened() {
        assertTrue(router.announce(3, 77).none { it is FileEvent.Received })
        assertTrue(received(router.incomingUpdate(77, TransferStatus.SUCCESS, 10, 10)).isEmpty())
        assertEquals(listOf(FileEvent.Received(3, 77)), received(router.copyFinished(77, true)))
    }

    @Test
    fun everyArrivalOrderDeliversExactlyOnce() {
        val steps = listOf<(FileRouter) -> List<FileEvent>>(
            { it.announce(1, 5) },
            { it.incomingUpdate(5, TransferStatus.SUCCESS, 4, 4) },
            { it.copyFinished(5, true) }
        )
        val orders = listOf(
            listOf(0, 1, 2), listOf(0, 2, 1), listOf(1, 0, 2), listOf(1, 2, 0), listOf(2, 0, 1), listOf(2, 1, 0)
        )
        for (order in orders) {
            val r = FileRouter()
            val all = order.flatMap { steps[it](r) }
            assertEquals(listOf(FileEvent.Received(1, 5)), received(all), "order $order")
            // Nothing lingers: repeating the last step produces no second delivery.
            assertTrue(received(r.copyFinished(5, true)).isEmpty(), "order $order")
        }
    }

    @Test
    fun progressIsReportedOnceIndexIsKnown() {
        assertTrue(router.incomingUpdate(9, TransferStatus.IN_PROGRESS, 5, 100).isEmpty())
        val events = router.announce(2, 9)
        assertEquals(listOf(FileEvent.Progress(2, 5, 100, sending = false)), events)
    }

    @Test
    fun radioFailureReportsFailureAndForgetsTheSlot() {
        router.announce(4, 8)
        assertEquals(
            listOf(FileEvent.Failed(4, sending = false)),
            router.incomingUpdate(8, TransferStatus.FAILED, 0, 0)
        )
        // A late success and copy for the same id must not resurrect it.
        assertTrue(received(router.incomingUpdate(8, TransferStatus.SUCCESS, 1, 1)).isEmpty())
        assertTrue(received(router.copyFinished(8, true)).isEmpty())
    }

    @Test
    fun copyFailureReportsFailure() {
        router.announce(4, 8)
        router.incomingUpdate(8, TransferStatus.SUCCESS, 1, 1)
        assertEquals(listOf(FileEvent.Failed(4, sending = false)), router.copyFinished(8, false))
    }

    @Test
    fun failureBeforeHeaderIsReportedWhenHeaderArrives() {
        router.incomingUpdate(8, TransferStatus.FAILED, 0, 0)
        assertEquals(listOf(FileEvent.Failed(6, sending = false)), router.announce(6, 8))
    }

    @Test
    fun floodOfUnknownPayloadsIsCappedButKnownOnesStillWork() {
        repeat(2000) { router.incomingUpdate(it.toLong(), TransferStatus.IN_PROGRESS, 1, 1) }
        assertTrue(router.announce(0, 5000L).isEmpty())
        assertTrue(router.incomingUpdate(5000L, TransferStatus.SUCCESS, 1, 1).isEmpty())
        // An id already being tracked is unaffected by the cap.
        assertEquals(1, router.announce(1, 10L).size)
    }

    @Test
    fun outgoingProgressIsLabelledWithTheIndex() {
        router.sending(2, 40)
        assertEquals(
            listOf(FileEvent.Progress(2, 50, 100, sending = true)),
            router.outgoingUpdate(40, TransferStatus.IN_PROGRESS, 50, 100)
        )
    }

    @Test
    fun outgoingFailureAndUnknownPayloads() {
        router.sending(2, 40)
        assertEquals(listOf(FileEvent.Failed(2, sending = true)), router.outgoingUpdate(40, TransferStatus.FAILED, 0, 0))
        assertTrue(router.outgoingUpdate(40, TransferStatus.IN_PROGRESS, 1, 1).isEmpty())
        assertTrue(router.outgoingUpdate(999, TransferStatus.IN_PROGRESS, 1, 1).isEmpty())
    }

    @Test
    fun outgoingSuccessReportsFinalProgressThenForgets() {
        router.sending(1, 41)
        assertEquals(
            listOf(FileEvent.Progress(1, 100, 100, sending = true)),
            router.outgoingUpdate(41, TransferStatus.SUCCESS, 100, 100)
        )
        assertTrue(router.outgoingUpdate(41, TransferStatus.IN_PROGRESS, 1, 1).isEmpty())
    }

    @Test
    fun clearForgetsEverything() {
        router.announce(1, 5)
        router.sending(2, 6)
        router.clear()
        assertTrue(router.outgoingUpdate(6, TransferStatus.IN_PROGRESS, 1, 1).isEmpty())
        router.incomingUpdate(5, TransferStatus.SUCCESS, 1, 1)
        assertTrue(received(router.copyFinished(5, true)).isEmpty())
    }
}
