package com.ontik.livestudio

import android.content.Context
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.util.DateTime
import com.google.api.services.youtube.YouTube
import com.google.api.services.youtube.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class YouTubeAuthManager(private val context: Context) {

    companion object {
        private const val TAG = "YouTubeAuthManager"
    }

    private val scope = Scope("https://www.googleapis.com/auth/youtube")

    fun signInIntent() = GoogleSignIn.getClient(
        context,
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(scope)
            .build()
    ).signInIntent

    suspend fun createLiveStream(): Pair<String, String>? =
        withContext(Dispatchers.IO) {
            try {
                val account = GoogleSignIn.getLastSignedInAccount(context)
                    ?: return@withContext null

                val credential = GoogleAccountCredential.usingOAuth2(
                    context,
                    listOf(scope.toString())
                ).apply {
                    selectedAccount = account.account
                }

                val youtube = YouTube.Builder(
                    AndroidHttp.newCompatibleTransport(),
                    GsonFactory.getDefaultInstance(),
                    credential
                ).setApplicationName("ONTIK Live Studio").build()

                val timeStamp = System.currentTimeMillis()
                val title = "ONTIK Live Stream $timeStamp"

                // 1. Create the Live Broadcast
                val broadcastSnippet = LiveBroadcastSnippet().apply {
                    this.title = title
                    this.scheduledStartTime = DateTime(timeStamp)
                }
                val broadcastStatus = LiveBroadcastStatus().apply {
                    this.privacyStatus = "unlisted"
                }
                val broadcastContentDetails = LiveBroadcastContentDetails().apply {
                    this.enableAutoStart = true
                    this.enableAutoStop = true
                }
                val broadcast = LiveBroadcast().apply {
                    this.snippet = broadcastSnippet
                    this.status = broadcastStatus
                    this.contentDetails = broadcastContentDetails
                }
                val createdBroadcast = youtube.liveBroadcasts()
                    .insert(listOf("snippet", "status", "contentDetails"), broadcast)
                    .execute()

                // 2. Create the Live Stream ingestion
                val streamSnippet = LiveStreamSnippet().apply {
                    this.title = title
                }
                val cdn = CdnSettings().apply {
                    this.frameRate = "30fps"
                    this.resolution = "1080p"
                    this.ingestionType = "rtmp"
                }
                val stream = LiveStream().apply {
                    this.snippet = streamSnippet
                    this.cdn = cdn
                }
                val createdStream = youtube.liveStreams()
                    .insert(listOf("snippet", "cdn"), stream)
                    .execute()

                // 3. Bind the broadcast to the stream
                youtube.liveBroadcasts()
                    .bind(createdBroadcast.id, listOf("id", "contentDetails"))
                    .setStreamId(createdStream.id)
                    .execute()

                val info = createdStream.cdn?.ingestionInfo
                    ?: return@withContext null

                val address = info.ingestionAddress
                    ?: return@withContext null

                val key = info.streamName
                    ?: return@withContext null

                Pair(address, key)
            } catch (e: Exception) {
                Log.e(TAG, "Error creating YouTube live broadcast: ${e.message}", e)
                null
            }
        }
}
