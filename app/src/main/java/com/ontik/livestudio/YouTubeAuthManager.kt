package com.ontik.livestudio

import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.services.youtube.YouTube
import com.google.api.services.youtube.model.IngestionInfo
import com.google.api.services.youtube.model.LiveStream
import com.google.api.services.youtube.model.LiveStreamCdn
import com.google.api.services.youtube.model.LiveStreamSnippet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class YouTubeAuthManager(private val context: Context) {

    private val scope = Scope("https://www.googleapis.com/auth/youtube")

    fun signInIntent() = GoogleSignIn.getClient(
        context,
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(scope)
            .build()
    ).signInIntent

    suspend fun createLiveStream(): Pair<String, String>? = withContext(Dispatchers.IO) {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return@withContext null
        val credential = GoogleAccountCredential.usingOAuth2(
            context, listOf(scope.toString())
        ).apply { selectedAccount = account.account }

        val youtube = YouTube.Builder(
            credential.transport,
            credential.jsonFactory,
            credential
        ).setApplicationName("ONTIK Live Studio").build()

        val stream = LiveStream().apply {
            snippet = LiveStreamSnippet().apply { title = "ONTIK ${System.currentTimeMillis()}" }
            cdn = LiveStreamCdn().apply {
                frameRate = "30"
                resolution = "1080p"
                ingestionType = "rtmp"
                ingestionInfo = IngestionInfo().apply {
                    streamName = "ontik-${System.currentTimeMillis()}"
                }
            }
        }
        val resp = youtube.liveStreams().insert("snippet,cdn", stream).execute()
        val info = resp.cdn.ingestionInfo
        Pair(info.ingestionAddress, info.streamName)
    }
}
