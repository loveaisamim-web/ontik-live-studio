package com.ontik.livestudio

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.youtube.YouTube
import com.google.api.services.youtube.YouTubeScopes
import com.google.api.services.youtube.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class YouTubeAuthManager(private val context: Context) {

    private val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestEmail()
        .requestScopes(Scope(YouTubeScopes.YOUTUBE))
        .build()

    private val signInClient: GoogleSignInClient = GoogleSignIn.getClient(context, gso)
    private var lastBroadcastId: String? = null

    fun signInIntent(): Intent = signInClient.signInIntent

    private fun getYouTubeService(): YouTube? {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return null
        val credential = GoogleAccountCredential.usingOAuth2(
            context, listOf(YouTubeScopes.YOUTUBE)
        ).apply {
            selectedAccount = account.account
        }
        return YouTube.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), credential)
            .setApplicationName("ONTIK Live Studio")
            .build()
    }

    suspend fun createLiveStream(): Pair<String, String>? = withContext(Dispatchers.IO) {
        try {
            val yt = getYouTubeService() ?: return@withContext null
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())

            val broadcastSnippet = LiveBroadcastSnippet().apply {
                title = "ONTIK Live - $time"
                scheduledStartTime = com.google.api.client.util.DateTime(System.currentTimeMillis())
            }
            val status = LiveBroadcastStatus().apply { privacyStatus = "public" }
            val broadcast = LiveBroadcast().apply {
                snippet = broadcastSnippet
                this.status = status
                kind = "youtube#liveBroadcast"
            }

            val insertedBroadcast = yt.liveBroadcasts().insert(listOf("snippet", "status"), broadcast).execute()
            lastBroadcastId = insertedBroadcast.id

            val streamSnippet = LiveStreamSnippet().apply { title = "ONTIK Stream - $time" }
            val cdn = CdnSettings().apply {
                format = "1080p"
                ingestionType = "rtmp"
                resolution = "variable"
                frameRate = "variable"
            }
            val stream = LiveStream().apply {
                snippet = streamSnippet
                cdn = cdn
                kind = "youtube#liveStream"
            }

            val insertedStream = yt.liveStreams().insert(listOf("snippet", "cdn"), stream).execute()
            yt.liveBroadcasts().bind(insertedBroadcast.id, listOf("id", "contentDetails"))
                .setStreamId(insertedStream.id).execute()

            val ing = insertedStream.cdn.ingestionInfo
            Pair(ing.ingestionAddress, ing.streamName)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun getLiveViewerCount(): Long? = withContext(Dispatchers.IO) {
        val bId = lastBroadcastId ?: return@withContext null
        try {
            val yt = getYouTubeService() ?: return@withContext null
            val resp = yt.videos().list(listOf("liveStreamingDetails"))
                .setId(listOf(bId))
                .execute()
            val video = resp.items.firstOrNull() ?: return@withContext null
            video.liveStreamingDetails?.concurrentViewers?.toLong()
        } catch (_: Exception) {
            null
        }
    }
}
