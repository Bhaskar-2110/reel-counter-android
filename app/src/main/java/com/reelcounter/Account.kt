package com.reelcounter

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialManagerCallback
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider

/**
 * Google sign-in through Firebase Auth. Sign-in only for now: nothing else is uploaded, and
 * counts, limits and history stay on the phone.
 *
 * Needs the Firebase project's google-services.json at build time; without it [isSetUp] is
 * false and [signIn] reports that instead of crashing.
 */
object Account {

    private const val TAG = "Account"

    fun isSetUp(ctx: Context): Boolean = FirebaseApp.getApps(ctx).isNotEmpty() && webClientId(ctx) != null

    fun current(ctx: Context): FirebaseUser? =
        if (FirebaseApp.getApps(ctx).isEmpty()) null else FirebaseAuth.getInstance().currentUser

    /** The name to show for [user]: their Google name, or their email when there's none. */
    fun label(user: FirebaseUser): String = user.displayName?.takeIf { it.isNotBlank() } ?: user.email.orEmpty()

    /**
     * Shows Google's account picker and signs in to Firebase with the chosen account. [onDone]
     * gets the user, or an error to show; both are null when the person closed the picker.
     */
    fun signIn(activity: Activity, onDone: (user: FirebaseUser?, error: String?) -> Unit) {
        val clientId = webClientId(activity)
        if (clientId == null || !isSetUp(activity)) {
            onDone(null, activity.getString(R.string.sign_in_not_set_up))
            return
        }
        val failed = activity.getString(R.string.sign_in_failed)
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(clientId).build())
            .build()
        CredentialManager.create(activity).getCredentialAsync(
            activity, request, null, ContextCompat.getMainExecutor(activity),
            object : CredentialManagerCallback<GetCredentialResponse, GetCredentialException> {
                override fun onResult(result: GetCredentialResponse) {
                    val credential = result.credential
                    if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                        Log.w(TAG, "Unexpected credential type ${credential.type}")
                        onDone(null, failed)
                        return
                    }
                    val idToken = try {
                        GoogleIdTokenCredential.createFrom(credential.data).idToken
                    } catch (e: Exception) {
                        Log.w(TAG, "Couldn't read the Google ID token", e)
                        onDone(null, failed)
                        return
                    }
                    FirebaseAuth.getInstance().signInWithCredential(GoogleAuthProvider.getCredential(idToken, null))
                        .addOnCompleteListener(activity) { task ->
                            if (task.isSuccessful) {
                                Analytics.login(activity, task.result?.additionalUserInfo?.isNewUser == true)
                                onDone(task.result?.user, null)
                            } else {
                                Log.w(TAG, "Firebase sign-in failed", task.exception)
                                onDone(null, failed)
                            }
                        }
                }

                override fun onError(e: GetCredentialException) {
                    if (e is GetCredentialCancellationException) onDone(null, null) else {
                        Log.w(TAG, "Google sign-in failed", e)
                        onDone(null, failed)
                    }
                }
            },
        )
    }

    /** Signs out of Firebase and forgets the chosen Google account, so next time shows the picker. */
    fun signOut(ctx: Context) {
        if (FirebaseApp.getApps(ctx).isEmpty()) return
        Analytics.logout(ctx)
        FirebaseAuth.getInstance().signOut()
        CredentialManager.create(ctx).clearCredentialStateAsync(
            ClearCredentialStateRequest(), null, ContextCompat.getMainExecutor(ctx),
            object : CredentialManagerCallback<Void?, ClearCredentialException> {
                override fun onResult(result: Void?) {}
                override fun onError(e: ClearCredentialException) = Log.w(TAG, "Couldn't clear the credential state", e).let {}
            },
        )
    }

    /** The web client ID the google-services plugin puts in resources, or null without it. */
    private fun webClientId(ctx: Context): String? {
        val id = ctx.resources.getIdentifier("default_web_client_id", "string", ctx.packageName)
        return if (id == 0) null else ctx.getString(id).takeIf { it.isNotBlank() }
    }
}
