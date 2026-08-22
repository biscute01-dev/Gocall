package com.example.auth

import android.content.Context
import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class FriendsRepository(
    private val context: Context,
    customRtdbUrl: String? = null
) {
    companion object {
        private const val TAG = "FriendsRepository"
        private const val NODE_USERS = "users"
        private const val NODE_FRIENDS = "friends"
        private const val NODE_REQUESTS_RECEIVED = "friend_requests_received"
        private const val NODE_REQUESTS_SENT = "friend_requests_sent"
        private const val NODE_USER_CALLS = "user_calls"
        private const val NODE_CALLS = "calls"
    }

    private val database: FirebaseDatabase = if (!customRtdbUrl.isNullOrBlank()) {
        FirebaseDatabase.getInstance(customRtdbUrl)
    } else {
        FirebaseDatabase.getInstance()
    }

    private var activeIncomingCallListener: ValueEventListener? = null
    private var activeIncomingCallRef: DatabaseReference? = null

    private val _incomingCall = MutableStateFlow<CallInvitation?>(null)
    val incomingCall: StateFlow<CallInvitation?> = _incomingCall.asStateFlow()

    private var presenceConnectedListener: ValueEventListener? = null
    private var presenceRef: DatabaseReference? = null

    /**
     * Sets up real-time presence for the user (online / lastSeen)
     */
    fun setupPresence(uid: String) {
        if (uid.isBlank()) return
        val userStatusRef = database.getReference(NODE_USERS).child(uid)
        val connectedRef = database.getReference(".info/connected")

        presenceConnectedListener?.let { connectedRef.removeEventListener(it) }

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val connected = snapshot.getValue(Boolean::class.java) ?: false
                if (connected) {
                    val statusMap = mapOf(
                        "isOnline" to true,
                        "lastSeen" to System.currentTimeMillis()
                    )
                    userStatusRef.updateChildren(statusMap)

                    // On disconnect
                    userStatusRef.child("isOnline").onDisconnect().setValue(false)
                    userStatusRef.child("lastSeen").onDisconnect().setValue(ServerValue.TIMESTAMP)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "Presence listener cancelled: ${error.message}")
            }
        }
        presenceConnectedListener = listener
        connectedRef.addValueEventListener(listener)
    }

    /**
     * Clears presence when user logs out
     */
    fun clearPresence(uid: String) {
        if (uid.isNotBlank()) {
            val userStatusRef = database.getReference(NODE_USERS).child(uid)
            userStatusRef.updateChildren(
                mapOf(
                    "isOnline" to false,
                    "lastSeen" to System.currentTimeMillis()
                )
            )
        }
        val connectedRef = database.getReference(".info/connected")
        presenceConnectedListener?.let { connectedRef.removeEventListener(it) }
        presenceConnectedListener = null
    }

    /**
     * Real-time flow of current user's friends
     */
    fun observeFriends(uid: String): Flow<List<FriendUser>> = callbackFlow {
        if (uid.isBlank()) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val ref = database.getReference(NODE_FRIENDS).child(uid)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val friendsList = mutableListOf<FriendUser>()
                for (child in snapshot.children) {
                    val friend = child.getValue(FriendUser::class.java)
                    if (friend != null) {
                        friendsList.add(friend)
                    }
                }
                // Sort by online first, then alphabetical display name
                friendsList.sortWith(compareByDescending<FriendUser> { it.isOnline }.thenBy { it.displayName.lowercase() })
                trySend(friendsList)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "observeFriends onCancelled: ${error.message}")
                trySend(emptyList())
            }
        }

        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    /**
     * Real-time flow of incoming friend requests
     */
    fun observeIncomingRequests(uid: String): Flow<List<FriendRequest>> = callbackFlow {
        if (uid.isBlank()) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val ref = database.getReference(NODE_REQUESTS_RECEIVED).child(uid)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val requests = mutableListOf<FriendRequest>()
                for (child in snapshot.children) {
                    val req = child.getValue(FriendRequest::class.java)
                    if (req != null && req.status == "pending") {
                        requests.add(req)
                    }
                }
                requests.sortByDescending { it.timestamp }
                trySend(requests)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "observeIncomingRequests onCancelled: ${error.message}")
                trySend(emptyList())
            }
        }

        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    /**
     * Real-time flow of outgoing friend requests sent by this user
     */
    fun observeOutgoingRequests(uid: String): Flow<List<FriendRequest>> = callbackFlow {
        if (uid.isBlank()) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val ref = database.getReference(NODE_REQUESTS_SENT).child(uid)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val requests = mutableListOf<FriendRequest>()
                for (child in snapshot.children) {
                    val req = child.getValue(FriendRequest::class.java)
                    if (req != null && req.status == "pending") {
                        requests.add(req)
                    }
                }
                requests.sortByDescending { it.timestamp }
                trySend(requests)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "observeOutgoingRequests onCancelled: ${error.message}")
                trySend(emptyList())
            }
        }

        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    /**
     * Searches for registered users matching query (by username or display name)
     */
    suspend fun searchUsers(
        query: String,
        currentUserUid: String,
        currentFriends: List<FriendUser>,
        incomingRequests: List<FriendRequest>,
        outgoingRequests: List<FriendRequest>
    ): List<UserSearchResult> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim().lowercase().removePrefix("@")
        if (cleanQuery.isBlank()) return@withContext emptyList()

        val friendUids = currentFriends.map { it.uid }.toSet()
        val incomingUids = incomingRequests.map { it.senderUid }.toSet()
        val outgoingUids = outgoingRequests.map { it.receiverUid }.toSet()

        val results = mutableListOf<UserSearchResult>()

        try {
            val usersSnap = database.getReference(NODE_USERS).get().await()
            for (child in usersSnap.children) {
                val user = child.getValue(UserProfile::class.java) ?: continue
                if (user.uid.isBlank()) continue

                // Check match
                val usernameMatches = user.username.lowercase().contains(cleanQuery)
                val displayNameMatches = user.displayName.lowercase().contains(cleanQuery)

                if (usernameMatches || displayNameMatches) {
                    val status = when {
                        user.uid == currentUserUid -> FriendRelationshipStatus.SELF
                        friendUids.contains(user.uid) -> FriendRelationshipStatus.FRIENDS
                        outgoingUids.contains(user.uid) -> FriendRelationshipStatus.REQUEST_SENT
                        incomingUids.contains(user.uid) -> FriendRelationshipStatus.REQUEST_RECEIVED
                        else -> FriendRelationshipStatus.NOT_FRIENDS
                    }
                    results.add(UserSearchResult(user, status))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "searchUsers failed: ${e.message}", e)
        }

        results.sortedWith(
            compareBy<UserSearchResult> {
                when (it.relationship) {
                    FriendRelationshipStatus.FRIENDS -> 0
                    FriendRelationshipStatus.REQUEST_RECEIVED -> 1
                    FriendRelationshipStatus.NOT_FRIENDS -> 2
                    FriendRelationshipStatus.REQUEST_SENT -> 3
                    FriendRelationshipStatus.SELF -> 4
                }
            }.thenBy { it.user.displayName.lowercase() }
        )
    }

    /**
     * Sends a friend request from currentUser to targetUser
     */
    suspend fun sendFriendRequest(
        currentProfile: UserProfile,
        targetUser: UserProfile
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (currentProfile.uid == targetUser.uid) {
                return@withContext Result.failure(IllegalArgumentException("Cannot send friend request to yourself"))
            }

            val requestId = "${currentProfile.uid}_${targetUser.uid}"
            val request = FriendRequest(
                id = requestId,
                senderUid = currentProfile.uid,
                senderDisplayName = currentProfile.displayName,
                senderUsername = currentProfile.username,
                senderPhotoUrl = currentProfile.photoUrl,
                senderAvatarBase64 = currentProfile.avatarBase64,
                receiverUid = targetUser.uid,
                receiverDisplayName = targetUser.displayName,
                receiverUsername = targetUser.username,
                receiverPhotoUrl = targetUser.photoUrl,
                receiverAvatarBase64 = targetUser.avatarBase64,
                timestamp = System.currentTimeMillis(),
                status = "pending"
            )

            // Write to receiver's incoming requests and sender's sent requests
            val updates = mapOf(
                "$NODE_REQUESTS_RECEIVED/${targetUser.uid}/${currentProfile.uid}" to request,
                "$NODE_REQUESTS_SENT/${currentProfile.uid}/${targetUser.uid}" to request
            )

            database.reference.updateChildren(updates).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "sendFriendRequest failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Accepts an incoming friend request
     */
    suspend fun acceptFriendRequest(
        currentProfile: UserProfile,
        request: FriendRequest
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val senderUid = request.senderUid
            val myUid = currentProfile.uid

            // Retrieve sender's latest profile
            val senderSnap = database.getReference(NODE_USERS).child(senderUid).get().await()
            val senderProfile = senderSnap.getValue(UserProfile::class.java)

            val senderFriendEntry = FriendUser(
                uid = senderUid,
                displayName = senderProfile?.displayName ?: request.senderDisplayName,
                username = senderProfile?.username ?: request.senderUsername,
                email = senderProfile?.email ?: "",
                photoUrl = senderProfile?.photoUrl ?: request.senderPhotoUrl,
                avatarBase64 = senderProfile?.avatarBase64 ?: request.senderAvatarBase64,
                isOnline = false,
                lastSeen = System.currentTimeMillis(),
                addedAt = System.currentTimeMillis()
            )

            val myFriendEntryForSender = FriendUser(
                uid = myUid,
                displayName = currentProfile.displayName,
                username = currentProfile.username,
                email = currentProfile.email,
                photoUrl = currentProfile.photoUrl,
                avatarBase64 = currentProfile.avatarBase64,
                isOnline = true,
                lastSeen = System.currentTimeMillis(),
                addedAt = System.currentTimeMillis()
            )

            // Atomic batch update:
            // 1. Add to my friends
            // 2. Add to sender's friends
            // 3. Remove received request
            // 4. Remove sent request on sender side
            val updates = mapOf<String, Any?>(
                "$NODE_FRIENDS/$myUid/$senderUid" to senderFriendEntry,
                "$NODE_FRIENDS/$senderUid/$myUid" to myFriendEntryForSender,
                "$NODE_REQUESTS_RECEIVED/$myUid/$senderUid" to null,
                "$NODE_REQUESTS_SENT/$senderUid/$myUid" to null
            )

            database.reference.updateChildren(updates).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "acceptFriendRequest failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Rejects an incoming friend request
     */
    suspend fun rejectFriendRequest(
        myUid: String,
        request: FriendRequest
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val senderUid = request.senderUid
            val updates = mapOf<String, Any?>(
                "$NODE_REQUESTS_RECEIVED/$myUid/$senderUid" to null,
                "$NODE_REQUESTS_SENT/$senderUid/$myUid" to null
            )
            database.reference.updateChildren(updates).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "rejectFriendRequest failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Cancels an outgoing friend request
     */
    suspend fun cancelFriendRequest(
        myUid: String,
        receiverUid: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val updates = mapOf<String, Any?>(
                "$NODE_REQUESTS_RECEIVED/$receiverUid/$myUid" to null,
                "$NODE_REQUESTS_SENT/$myUid/$receiverUid" to null
            )
            database.reference.updateChildren(updates).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "cancelFriendRequest failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Unfriends a user
     */
    suspend fun removeFriend(
        myUid: String,
        friendUid: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val updates = mapOf<String, Any?>(
                "$NODE_FRIENDS/$myUid/$friendUid" to null,
                "$NODE_FRIENDS/$friendUid/$myUid" to null
            )
            database.reference.updateChildren(updates).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "removeFriend failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    // ==========================================
    // DIRECT USER-TO-USER CALL SIGNALING
    // ==========================================

    /**
     * Starts listening for incoming direct video calls
     */
    fun startListeningForIncomingCalls(myUid: String) {
        if (myUid.isBlank()) return
        stopListeningForIncomingCalls()

        val ref = database.getReference(NODE_USER_CALLS).child(myUid).child("incoming")
        activeIncomingCallRef = ref

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) {
                    val call = snapshot.getValue(CallInvitation::class.java)
                    if (call != null && call.status == "ringing") {
                        // Check if not expired (within 60 seconds)
                        if (System.currentTimeMillis() - call.timestamp < 60000) {
                            _incomingCall.value = call
                        } else {
                            // Call timed out, clear it
                            ref.setValue(null)
                            _incomingCall.value = null
                        }
                    } else {
                        _incomingCall.value = null
                    }
                } else {
                    _incomingCall.value = null
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "Incoming call listener cancelled: ${error.message}")
            }
        }
        activeIncomingCallListener = listener
        ref.addValueEventListener(listener)
    }

    fun stopListeningForIncomingCalls() {
        activeIncomingCallListener?.let { listener ->
            activeIncomingCallRef?.removeEventListener(listener)
        }
        activeIncomingCallListener = null
        activeIncomingCallRef = null
        _incomingCall.value = null
    }

    /**
     * Sends an incoming call invitation directly to friend's node
     */
    suspend fun sendCallInvitation(
        callInvitation: CallInvitation
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val calleeUid = callInvitation.calleeUid
            database.getReference(NODE_USER_CALLS)
                .child(calleeUid)
                .child("incoming")
                .setValue(callInvitation)
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "sendCallInvitation failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Dismisses/Clears incoming call for callee
     */
    suspend fun clearIncomingCall(myUid: String) = withContext(Dispatchers.IO) {
        try {
            database.getReference(NODE_USER_CALLS)
                .child(myUid)
                .child("incoming")
                .setValue(null)
                .await()
            _incomingCall.value = null
        } catch (e: Exception) {
            Log.w(TAG, "clearIncomingCall note: ${e.message}")
        }
    }

    /**
     * Callee rejects incoming call
     */
    suspend fun rejectCall(callInvitation: CallInvitation): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Update call room status to rejected
            database.getReference(NODE_CALLS)
                .child(callInvitation.callId)
                .child("status")
                .setValue("rejected")
                .await()

            // Clear incoming invitation for callee
            database.getReference(NODE_USER_CALLS)
                .child(callInvitation.calleeUid)
                .child("incoming")
                .setValue(null)
                .await()

            _incomingCall.value = null
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "rejectCall failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Caller cancels outgoing call before callee answers
     */
    suspend fun cancelOutgoingCall(
        callId: String,
        calleeUid: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Update call room status
            database.getReference(NODE_CALLS)
                .child(callId)
                .child("status")
                .setValue("cancelled")
                .await()

            // Clear callee's incoming call node
            database.getReference(NODE_USER_CALLS)
                .child(calleeUid)
                .child("incoming")
                .setValue(null)
                .await()

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "cancelOutgoingCall failed: ${e.message}", e)
            Result.failure(e)
        }
    }
}
