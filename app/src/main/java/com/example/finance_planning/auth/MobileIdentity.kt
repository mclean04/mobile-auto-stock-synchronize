package com.example.finance_planning.auth

import com.example.finance_planning.R
import com.example.finance_planning.core.AppText
import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.ClearCredentialStateRequest
import com.example.finance_planning.BuildConfig
import com.example.finance_planning.core.AppFailure
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.appcheck.FirebaseAppCheck
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

open class MobileIdentity(private val context: Context) {
    val configured: Boolean get() = BuildConfig.FIREBASE_APP_ID.isNotBlank() &&
        BuildConfig.FIREBASE_API_KEY.isNotBlank() && BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()
    fun initialize() {
        if (!configured) return
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(context, FirebaseOptions.Builder()
                .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                .setApiKey(BuildConfig.FIREBASE_API_KEY)
                .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID).build())
        }
        val provider = AppCheckBuildProvider.create()
        FirebaseAppCheck.getInstance().installAppCheckProviderFactory(provider)
    }
    open fun uid(): String? = if (configured) FirebaseAuth.getInstance().currentUser?.uid else null
    suspend fun signIn(activity: Context) {
        if (!configured) throw AppFailure(AppText.get(R.string.firebase_sign_in_not_configured_build))
        val option = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID).build()
        val result = try { CredentialManager.create(activity).getCredential(activity,
            GetCredentialRequest.Builder().addCredentialOption(option).build())
        } catch (_: androidx.credentials.exceptions.NoCredentialException) {
            throw AppFailure(AppText.get(R.string.no_eligible_google_account_on_this_device))
        } catch (_: androidx.credentials.exceptions.GetCredentialCancellationException) {
            throw AppFailure(AppText.get(R.string.sign_in_cancelled))
        }
        val google = GoogleIdTokenCredential.createFrom(result.credential.data)
        FirebaseAuth.getInstance().signInWithCredential(
            GoogleAuthProvider.getCredential(google.idToken, null)).await()
    }
    suspend fun headers(): Map<String, String> {
        if (!configured) throw AppFailure(AppText.get(R.string.firebase_sign_in_is_not_configured))
        val user = FirebaseAuth.getInstance().currentUser ?: throw AppFailure(AppText.get(R.string.sign_in_first))
        val token = user.getIdToken(false).await().token ?: throw AppFailure(AppText.get(R.string.your_sign_in_session_has_expired))
        val attestation = try {
            FirebaseAppCheck.getInstance().getAppCheckToken(false).await().token
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Never surface the SDK exception: it may contain credential or request details.
            val guidance = AppText.get(R.string.app_check_certificate_guidance)
            throw AppFailure(AppText.get(R.string.app_check_not_verified) +
                guidance + AppText.get(R.string.app_check_retry_guidance))
        }
        return mapOf("Authorization" to "Bearer $token", "X-Firebase-AppCheck" to attestation)
    }
    suspend fun signOut() {
        context.getSystemService(android.app.NotificationManager::class.java).cancelAll()
        if (configured) FirebaseAuth.getInstance().signOut()
        CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
    }
}
