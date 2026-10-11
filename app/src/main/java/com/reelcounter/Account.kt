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
import com.google.firebase.Timestamp
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.util.Date

/**
 * Google sign-in through Firebase Auth. The account's name, email and sign-in dates are kept in
 * Firestore (`users/{uid}`); counts, limits and history stay on the phone.
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
        val failed = activity.getString(R.string.sign_in_failed)
        googleCredential(activity, onError = { onDone(null, it) }) { credential ->
            FirebaseAuth.getInstance().signInWithCredential(credential)
                .addOnCompleteListener(activity) { task ->
                    val user = task.result?.user
                    if (task.isSuccessful && user != null) {
                        val isNew = task.result?.additionalUserInfo?.isNewUser == true
                        Analytics.setUser(activity, user.uid)
                        Analytics.login(activity, isNew)
                        saveProfile(activity, user)
                        onDone(user, null)
                    } else {
                        Log.w(TAG, "Firebase sign-in failed", task.exception)
                        onDone(null, failed)
                    }
                }
        }
    }

    /** Signs out of Firebase and forgets the chosen Google account, so next time shows the picker. */
    fun signOut(ctx: Context) {
        if (FirebaseApp.getApps(ctx).isEmpty()) return
        Analytics.logout(ctx)
        Analytics.setUser(ctx, null)
        FirebaseAuth.getInstance().signOut()
        CredentialManager.create(ctx).clearCredentialStateAsync(
            ClearCredentialStateRequest(), null, ContextCompat.getMainExecutor(ctx),
            object : CredentialManagerCallback<Void?, ClearCredentialException> {
                override fun onResult(result: Void?) {}
                override fun onError(e: ClearCredentialException) = Log.w(TAG, "Couldn't clear the credential state", e).let {}
            },
        )
    }

    /**
     * Deletes the account: its profile in Firestore, then the Firebase user. Firebase asks for a
     * recent sign-in before deleting, so the Google picker may show once more. [onDone] gets
     * whether it was deleted, and an error to show (null when the person closed the picker).
     */
    fun deleteAccount(activity: Activity, onDone: (deleted: Boolean, error: String?) -> Unit) {
        val user = current(activity) ?: return onDone(false, null)
        val failed = activity.getString(R.string.delete_account_failed)
        profile(user.uid).delete().addOnCompleteListener(activity) { task ->
            if (task.isSuccessful) deleteUser(activity, user, reauthenticated = false, failed, onDone) else {
                Log.w(TAG, "Couldn't delete the profile", task.exception)
                onDone(false, failed)
            }
        }
    }

    private fun deleteUser(
        activity: Activity,
        user: FirebaseUser,
        reauthenticated: Boolean,
        failed: String,
        onDone: (deleted: Boolean, error: String?) -> Unit,
    ) {
        user.delete().addOnCompleteListener(activity) { task ->
            when {
                task.isSuccessful -> {
                    Analytics.accountDeleted(activity)
                    signOut(activity)
                    onDone(true, null)
                }
                task.exception is FirebaseAuthRecentLoginRequiredException && !reauthenticated ->
                    googleCredential(activity, onError = { onDone(false, it) }) { credential ->
                        user.reauthenticate(credential).addOnCompleteListener(activity) { again ->
                            if (again.isSuccessful) deleteUser(activity, user, reauthenticated = true, failed, onDone) else {
                                Log.w(TAG, "Couldn't sign in again to delete", again.exception)
                                onDone(false, failed)
                            }
                        }
                    }
                else -> {
                    Log.w(TAG, "Couldn't delete the account", task.exception)
                    onDone(false, failed)
                }
            }
        }
    }

    /**
     * The person's profile (name, email, sign-in dates) in the project's own Firestore, so the
     * user ID on analytics events can be matched to a person. Never put in analytics events.
     */
    private fun saveProfile(ctx: Context, user: FirebaseUser) {
        val data = hashMapOf<String, Any>(
            "email" to user.email.orEmpty(),
            "name" to user.displayName.orEmpty(),
            "lastSignInAt" to FieldValue.serverTimestamp(),
            "appVersion" to appVersion(ctx),
        )
        // When the account was made, from Firebase itself: right even for profiles saved later.
        user.metadata?.creationTimestamp?.let { data["createdAt"] = Timestamp(Date(it)) }
        profile(user.uid).set(data, SetOptions.merge())
            .addOnFailureListener { Log.w(TAG, "Couldn't save the profile", it) }
    }

    private fun profile(uid: String) = FirebaseFirestore.getInstance().collection("users").document(uid)

    private fun appVersion(ctx: Context) = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName.orEmpty()
    } catch (e: Exception) {
        ""
    }

    /**
     * Shows Google's account picker and hands the chosen account to [onCredential] as a Firebase
     * credential. [onError] gets an error to show, or null when the person closed the picker.
     */
    private fun googleCredential(activity: Activity, onError: (String?) -> Unit, onCredential: (AuthCredential) -> Unit) {
        val clientId = webClientId(activity)
        if (clientId == null || !isSetUp(activity)) {
            onError(activity.getString(R.string.sign_in_not_set_up))
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
                        onError(failed)
                        return
                    }
                    val idToken = try {
                        GoogleIdTokenCredential.createFrom(credential.data).idToken
                    } catch (e: Exception) {
                        Log.w(TAG, "Couldn't read the Google ID token", e)
                        onError(failed)
                        return
                    }
                    onCredential(GoogleAuthProvider.getCredential(idToken, null))
                }

                override fun onError(e: GetCredentialException) {
                    if (e is GetCredentialCancellationException) onError(null) else {
                        Log.w(TAG, "Google sign-in failed", e)
                        onError(failed)
                    }
                }
            },
        )
    }

    /** The web client ID the google-services plugin puts in resources, or null without it. */
    private fun webClientId(ctx: Context): String? {
        val id = ctx.resources.getIdentifier("default_web_client_id", "string", ctx.packageName)
        return if (id == 0) null else ctx.getString(id).takeIf { it.isNotBlank() }
    }
}
