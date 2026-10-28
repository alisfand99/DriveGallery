package app.drivegallery

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await
import java.io.IOException

class AuthNeeded : IOException("Drive authorization is required")

object Auth {
    /** The app only sees files it created itself, which is all it needs. */
    private const val SCOPE_DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"

    @Volatile
    var cachedToken: String? = null

    fun request(): AuthorizationRequest =
        AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(SCOPE_DRIVE_FILE)))
            .build()

    /** Returns an access token without any UI, or null when the user must tap through a consent screen. */
    suspend fun silentToken(ctx: Context): String? {
        val result = Identity.getAuthorizationClient(ctx.applicationContext).authorize(request()).await()
        if (result.hasResolution()) return null
        cachedToken = result.accessToken
        return cachedToken
    }
}
