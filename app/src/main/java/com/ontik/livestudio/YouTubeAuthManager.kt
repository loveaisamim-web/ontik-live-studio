package com.ontik.livestudio

import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.youtube.YouTube
import com.google.api.services.youtube.model.LiveStream
import com.google.api.services.youtube.model.CdnSettings
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

    suspend fun createLiveStream(): Pair<String, String>? =
        withContext(Dispatchers.IO) {
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

            val title = "ONTIK ${System.currentTimeMillis()}"

            val snippet = LiveStreamSnippet().setTitle(title)

            val cdn = CdnSettings()
                .setFrameRate("30fps")
                .setResolution("1080p")
                .setIngestionType("rtmp")

            val stream = LiveStream()
                .setSnippet(snippet)
                .setCdn(cdn)

            val response = youtube.liveStreams()
                .insert(listOf("snippet", "cdn"), stream)
                .execute()

            val info = response.cdn?.ingestionInfo
                ?: return@withContext null

            val address = info.ingestionAddress
                ?: return@withContext null

            val key = info.streamName
                ?: return@withContext null

            Pair(address, key)
        }
}
