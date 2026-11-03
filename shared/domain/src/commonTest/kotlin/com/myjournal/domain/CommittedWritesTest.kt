package com.myjournal.domain

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CommittedWritesTest {
    @Test fun snapshotCannotProceedUntilAllFeatureWritesHaveCommitted() = runTest {
        var journal=1;var planner=1;var habits=1;var notebook=1;var captured=false
        val job=launch { awaitCommittedWrites { SaveStatus(journal+planner+habits+notebook,null) };captured=true }
        runCurrent();assertFalse(captured)
        journal=0;planner=0;habits=0;advanceTimeBy(40);assertFalse(captured)
        notebook=0;advanceUntilIdle();assertTrue(captured);job.join()
    }
    @Test fun failedUnsavedWritesRejectBackupEvenWhenOtherQueuesAreEmpty() = runTest {
        assertFailsWith<IllegalStateException> { awaitCommittedWrites { SaveStatus(1,"storage full") } }
        assertFailsWith<IllegalStateException> { awaitCommittedWrites { SaveStatus(0,"Cannot load page") } }
    }
    @Test fun stuckWritesTimeOutAndNeverAuthorizeAnIncompleteSnapshot() = runTest {
        var captured=false
        assertFailsWith<TimeoutCancellationException> { awaitCommittedWrites(100) { SaveStatus(1,null) };captured=true }
        assertFalse(captured)
    }
}
