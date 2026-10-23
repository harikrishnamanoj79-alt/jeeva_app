package com.kryptup.kryptup.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RewardsRepository @Inject constructor(
    private val functions: FirebaseFunctions,
    private val auth: FirebaseAuth
) {

    /**
     * Calls the [claimDailyReward] Cloud Function to enforce 24-hour streak server-side
     * and mutate points via Firebase Admin SDK.
     */
    suspend fun claimDailyLoginReward(): Boolean = withContext(Dispatchers.IO) {
        val uid = auth.currentUser?.uid ?: return@withContext false
        try {
            val result = functions
                .getHttpsCallable("claimDailyReward")
                .call()
                .await()

            @Suppress("UNCHECKED_CAST")
            val data = result.data as? Map<String, Any>
            data?.get("success") == true
        } catch (e: Exception) {
            android.util.Log.e("RewardsRepository", "Failed to claim daily reward: ${e.message}", e)
            false
        }
    }
}
