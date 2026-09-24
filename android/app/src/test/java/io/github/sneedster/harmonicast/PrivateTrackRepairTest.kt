package io.github.sneedster.harmonicast

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PrivateTrackRepairTest {
    private class Fixture {
        val storage = AcquisitionMemory()
        val source = PersonalPlexSource("token", "https://plex", "machine", "Plex", "7", "Music")
        var valid = true
        var losePost = false
        var id = ""
        var status = "ready"
        var machine = "machine"
        var posts = 0
        var reads = 0
        var fileGate: CompletableDeferred<Unit>? = null
        val song = Song("plex:machine:42", "Halcyon", "Orbital")
        fun response() = JSONObject().put("id", id).put("state", status).put("title", song.title).put("artist", song.artist)
            .put("message", "Test status").put("choices", JSONArray().put(JSONObject().put("id", 0).put("title", "Halcyon").put("source", "qobuz")))
        fun client(connection: String = "account") = PrivateTrackRepair(source, storage, connection, { valid },
            file = { fileGate?.await(); "/plex/music/song.flac" },
            call = { path, method, body ->
                if (path.endsWith("capability")) JSONObject().put("enabled", true).put("machine", machine).put("library", "7")
                else {
                    if (method == "POST") {
                        posts++
                        if (path.endsWith("/requests")) id = body!!.getString("request_id") else status = "done"
                        if (losePost) throw IOException("Response lost")
                    } else reads++
                    response()
                }
            }, pause = {})
    }

    @Test fun featureRemainsHiddenForOtherLibrary() = runBlocking {
        val f = Fixture(); f.machine = "other"
        val model = f.client(); model.checkEnabled(); model.search(f.song)
        assertFalse(model.state.value.enabled)
        assertEquals(0, f.posts)
    }
    @Test fun lostResponseRecoversExistingRequestWithoutResubmitting() = runBlocking {
        val f = Fixture(); val model = f.client(); model.checkEnabled(); f.losePost = true
        model.search(f.song)
        assertEquals("unconfirmed", model.state.value.status)
        assertEquals(1, f.posts)
        model.search(f.song)
        assertEquals(1, f.posts)
        f.losePost = false
        val reopened = f.client(); reopened.checkEnabled(); reopened.open(f.song)
        assertEquals("ready", reopened.state.value.status)
        assertEquals(1, f.posts)
        assertEquals(1, f.reads)
    }
    @Test fun lostReplacementResponseDoesNotApplyAgain() = runBlocking {
        val f = Fixture(); val model = f.client(); model.checkEnabled(); model.search(f.song)
        f.losePost = true; model.replace(0); model.replace(0)
        assertEquals(2, f.posts)
        assertEquals("unconfirmed", model.state.value.status)
        f.losePost = false; model.refresh()
        assertEquals("done", model.state.value.status)
        assertEquals(2, f.posts)
    }
    @Test fun sourceChangeWhileResolvingFilePreventsSubmission() = runBlocking {
        val f = Fixture(); f.fileGate = CompletableDeferred()
        val model = f.client(); model.checkEnabled()
        val pending = launch(start = CoroutineStart.UNDISPATCHED) { model.search(f.song) }
        f.valid = false; f.fileGate!!.complete(Unit); pending.join()
        assertEquals(0, f.posts)
        assertEquals("failed", model.state.value.status)
    }
    @Test fun duplicateTapAndInvalidChoiceAreIgnored() = runBlocking {
        val f = Fixture(); f.fileGate = CompletableDeferred()
        val model = f.client(); model.checkEnabled()
        val pending = launch(start = CoroutineStart.UNDISPATCHED) { model.search(f.song) }
        model.search(f.song); f.fileGate!!.complete(Unit); pending.join()
        model.replace(7)
        assertEquals(1, f.posts)
        model.replace(0); model.replace(0)
        assertEquals(2, f.posts)
    }
    @Test fun pendingRequestsNeverLeakAcrossAccounts() = runBlocking {
        val f = Fixture(); val first = f.client(); first.checkEnabled(); first.search(f.song)
        val second = f.client("other-account"); second.checkEnabled(); second.open(f.song)
        assertEquals(2, f.posts)
        assertEquals(0, f.reads)
    }
}
