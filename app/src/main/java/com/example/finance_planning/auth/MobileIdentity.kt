package com.example.finance_planning.auth

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
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

class MobileIdentity(private val context: Context) {
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
        val provider = if (BuildConfig.DEBUG) DebugAppCheckProviderFactory.getInstance()
            else PlayIntegrityAppCheckProviderFactory.getInstance()
        FirebaseAppCheck.getInstance().installAppCheckProviderFactory(provider)
    }
    fun uid(): String? = if (configured) FirebaseAuth.getInstance().currentUser?.uid else null
    suspend fun signIn(activity: Context) {
        if (!configured) throw AppFailure("Chưa cấu hình đăng nhập Firebase cho bản cài này.")
        val option = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID).build()
        val result = try { CredentialManager.create(activity).getCredential(activity,
            GetCredentialRequest.Builder().addCredentialOption(option).build())
        } catch (_: androidx.credentials.exceptions.NoCredentialException) {
            throw AppFailure("Chưa có tài khoản Google phù hợp trên thiết bị.")
        } catch (_: androidx.credentials.exceptions.GetCredentialCancellationException) {
            throw AppFailure("Bạn đã hủy đăng nhập.")
        }
        val google = GoogleIdTokenCredential.createFrom(result.credential.data)
        FirebaseAuth.getInstance().signInWithCredential(
            GoogleAuthProvider.getCredential(google.idToken, null)).await()
    }
    suspend fun headers(): Map<String, String> {
        if (!configured) throw AppFailure("Chưa cấu hình đăng nhập Firebase.")
        val user = FirebaseAuth.getInstance().currentUser ?: throw AppFailure("Hãy đăng nhập trước.")
        val token = user.getIdToken(false).await().token ?: throw AppFailure("Phiên đăng nhập đã hết hạn.")
        val attestation = try {
            FirebaseAppCheck.getInstance().getAppCheckToken(false).await().token
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Never surface the SDK exception: it may contain credential or request details.
            val guidance = if (BuildConfig.DEBUG)
                "Với bản debug, kiểm tra đăng ký debug token của thiết bị trong Firebase App Check. "
            else "Kiểm tra kết nối và cấu hình Play Integrity của bản cài. "
            throw AppFailure("Google đã đăng nhập, nhưng chưa xác minh được ứng dụng bằng App Check. " +
                guidance + "Nếu vừa thử nhiều lần, chờ rồi bấm Kiểm tra quyền.")
        }
        return mapOf("Authorization" to "Bearer $token", "X-Firebase-AppCheck" to attestation)
    }
    suspend fun signOut() {
        context.getSystemService(android.app.NotificationManager::class.java).cancelAll()
        if (configured) FirebaseAuth.getInstance().signOut()
        CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
    }
}
