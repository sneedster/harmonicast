package io.github.sneedster.harmonicast

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PrivateTrackRepairTest {
    private class Fixture {
        val storage = AcquisitionMemory()
        var valid = true
        var connected = true
        var loseDelete = false
        var losePost = false
        var rejectPost = false
        var exists = true
        var completed = true
        var failStatus = false
        var deletions = 0
        var posts = 0
        var reads = 0
        var fileGate: CompletableDeferred<Unit>? = null
        val events = mutableListOf<String>()
        val song = Song("plex:machine:42", "Halcyon", "Orbital")
        val original = BadPlexFile(song.id, "/music/song.flac", "123", "1000")
        fun client(scope: String = "account|machine|7") = PrivateTrackRepair(storage, scope, { valid },
            checkAccount = { connected },
            file = { fileGate?.await(); original },
            delete = {
                assertEquals(original, it); events += "delete"; deletions++; exists = false
                if (loseDelete) throw IOException("Delete response lost")
            },
            deleted = { events += "verify"; !exists },
            call = { path, method, body ->
                if (method == "POST") {
                    assertEquals("/api/bulk-import-async", path)
                    assertEquals("Orbital - Halcyon", body!!.getString("songs"))
                    assertFalse(body.getBoolean("create_playlist"))
                    assertFalse(body.getBoolean("use_playlists_dir"))
                    assertFalse(exists)
                    events += "request"; posts++
                    if (rejectPost) throw AcquisitionFailure(403, "Rejected")
                    if (losePost) throw IOException("Response lost")
                    JSONObject().put("import_id", "import-1")
                } else {
                    reads++
                    assertEquals("/api/bulk-import/import-1/status", path)
                    if (failStatus) throw IOException("Offline")
                    JSONObject().put("complete", completed).put("completed", if (completed) 1 else 0)
                }
            }, pause = { delay(1) })
    }

    @Test fun deletesVerifiesThenRequestsThroughUnmodifiedUpstreamApi() = runBlocking {
        val f = Fixture(); val model = f.client(); model.open(f.song)
        assertEquals("ready", model.state.value.status)
        assertEquals(0, f.deletions)
        model.replace()
        assertEquals(listOf("delete", "verify", "request"), f.events)
        assertEquals("done", model.state.value.status)
        assertEquals(1, f.deletions)
    }
    @Test fun uncertainDeletionIsReadBackBeforeAnyDownloadAndNeverDeletedTwice() = runBlocking {
        val f = Fixture(); f.loseDelete = true
        val model = f.client(); model.open(f.song); model.replace()
        assertEquals("delete_unknown", model.state.value.status)
        assertEquals(0, f.posts)
        val reopened = f.client(); reopened.open(f.song); reopened.refresh()
        assertEquals("done", reopened.state.value.status)
        assertEquals(1, f.deletions)
        assertEquals(1, f.posts)
    }
    @Test fun lostImportResponseNeverResubmitsOrDeletesAgain() = runBlocking {
        val f = Fixture(); f.losePost = true
        val model = f.client(); model.open(f.song); model.replace(); model.replace()
        assertEquals("request_unknown", model.state.value.status)
        val reopened = f.client(); reopened.open(f.song); reopened.refresh(); reopened.replace()
        assertEquals("request_unknown", reopened.state.value.status)
        assertEquals(1, f.deletions)
        assertEquals(1, f.posts)
    }
    @Test fun failedSubmissionRetriesOnlyDownloadNotDeletion() = runBlocking {
        val f = Fixture(); f.rejectPost = true
        val model = f.client(); model.open(f.song); model.replace()
        assertEquals("request_failed", model.state.value.status)
        f.rejectPost = false
        model.refresh()
        assertEquals("done", model.state.value.status)
        assertEquals(1, f.deletions)
        assertEquals(2, f.posts)
    }
    @Test fun unavailableMusicGrabberNeverDeletesFile() = runBlocking {
        val f = Fixture(); val model = f.client(); model.open(f.song)
        f.connected = false; model.replace()
        assertEquals(0, f.deletions)
        assertEquals(0, f.posts)
    }
    @Test fun duplicateTapWhileDownloadingDoesNothing() = runBlocking {
        val f = Fixture(); f.completed = false
        val model = f.client(); model.open(f.song)
        val job = launch(start = CoroutineStart.UNDISPATCHED) { model.replace() }
        model.replace(); f.completed = true; job.join()
        assertEquals(1, f.deletions)
        assertEquals(1, f.posts)
    }
    @Test fun accountOrSourceChangePreventsDeletion() = runBlocking {
        val f = Fixture(); val model = f.client(); model.open(f.song)
        f.valid = false; model.replace()
        assertEquals(0, f.deletions)
        assertEquals(0, f.posts)
    }
    @Test fun unsupportedArtistIsRejectedBeforeDeletion() = runBlocking {
        val f = Fixture(); val model = f.client(); model.open(f.song.copy(artist = "Artist - Other")); model.replace()
        assertEquals("blocked", model.state.value.status)
        assertEquals(0, f.deletions)
    }
    @Test fun downloadStatusRecoversWithoutAnotherRequest() = runBlocking {
        val f = Fixture(); f.failStatus = true
        val model = f.client(); model.open(f.song); model.replace()
        assertEquals("acquiring", model.state.value.status)
        f.failStatus = false
        val reopened = f.client(); reopened.open(f.song); reopened.refresh()
        assertEquals("done", reopened.state.value.status)
        assertEquals(1, f.posts)
        assertEquals(1, f.deletions)
    }
    @Test fun activationIsOffByDefaultAndPersistsAfterSevenQuickTaps() {
        val storage = AcquisitionMemory(); val toggle = PrivateRepairSwitch(storage)
        assertFalse(toggle.enabled)
        repeat(6) { assertNull(toggle.tap(it * 100L)) }
        assertEquals(true, toggle.tap(600))
        assertTrue(PrivateRepairSwitch(storage).enabled)
        repeat(6) { toggle.tap(1000L + it * 100) }
        assertEquals(false, toggle.tap(1600))
    }
    @Test fun widelySpacedVersionTapsDoNotEnableAnything() {
        val toggle = PrivateRepairSwitch(AcquisitionMemory())
        repeat(10) { assertNull(toggle.tap(it * 5000L)) }
        assertFalse(toggle.enabled)
    }
}
