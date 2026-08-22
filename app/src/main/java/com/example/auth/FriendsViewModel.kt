package com.example.auth

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class FriendsViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val PREFS_NAME = "video_call_prefs"
        private const val KEY_CUSTOM_RTDB = "custom_rtdb_url"
    }

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val friendsRepository: FriendsRepository = FriendsRepository(
        application,
        prefs.getString(KEY_CUSTOM_RTDB, null)
    )

    private val _friends = MutableStateFlow<List<FriendUser>>(emptyList())
    val friends: StateFlow<List<FriendUser>> = _friends.asStateFlow()

    private val _incomingRequests = MutableStateFlow<List<FriendRequest>>(emptyList())
    val incomingRequests: StateFlow<List<FriendRequest>> = _incomingRequests.asStateFlow()

    private val _outgoingRequests = MutableStateFlow<List<FriendRequest>>(emptyList())
    val outgoingRequests: StateFlow<List<FriendRequest>> = _outgoingRequests.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<UserSearchResult>>(emptyList())
    val searchResults: StateFlow<List<UserSearchResult>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _actionError = MutableStateFlow<String?>(null)
    val actionError: StateFlow<String?> = _actionError.asStateFlow()

    private val _actionSuccess = MutableStateFlow<String?>(null)
    val actionSuccess: StateFlow<String?> = _actionSuccess.asStateFlow()

    private var currentProfile: UserProfile? = null
    private var friendsJob: Job? = null
    private var incomingRequestsJob: Job? = null
    private var outgoingRequestsJob: Job? = null
    private var searchJob: Job? = null

    fun setUserProfile(profile: UserProfile?) {
        this.currentProfile = profile
        friendsJob?.cancel()
        incomingRequestsJob?.cancel()
        outgoingRequestsJob?.cancel()

        if (profile != null && profile.uid.isNotBlank()) {
            friendsRepository.setupPresence(profile.uid)
            friendsRepository.startListeningForIncomingCalls(profile.uid)

            friendsJob = viewModelScope.launch {
                friendsRepository.observeFriends(profile.uid).collect { list ->
                    _friends.value = list
                    // Re-run search results formatting if search query active
                    if (_searchQuery.value.isNotBlank()) {
                        executeSearch(_searchQuery.value)
                    }
                }
            }

            incomingRequestsJob = viewModelScope.launch {
                friendsRepository.observeIncomingRequests(profile.uid).collect { list ->
                    _incomingRequests.value = list
                }
            }

            outgoingRequestsJob = viewModelScope.launch {
                friendsRepository.observeOutgoingRequests(profile.uid).collect { list ->
                    _outgoingRequests.value = list
                }
            }
        } else {
            _friends.value = emptyList()
            _incomingRequests.value = emptyList()
            _outgoingRequests.value = emptyList()
            friendsRepository.stopListeningForIncomingCalls()
        }
    }

    fun onSearchQueryChanged(newQuery: String) {
        _searchQuery.value = newQuery
        searchJob?.cancel()
        if (newQuery.isBlank()) {
            _searchResults.value = emptyList()
            _isSearching.value = false
            return
        }

        searchJob = viewModelScope.launch {
            _isSearching.value = true
            delay(300) // Debounce search
            executeSearch(newQuery)
            _isSearching.value = false
        }
    }

    private suspend fun executeSearch(query: String) {
        val profile = currentProfile ?: return
        val results = friendsRepository.searchUsers(
            query = query,
            currentUserUid = profile.uid,
            currentFriends = _friends.value,
            incomingRequests = _incomingRequests.value,
            outgoingRequests = _outgoingRequests.value
        )
        _searchResults.value = results
    }

    fun sendFriendRequest(targetUser: UserProfile) {
        val myProfile = currentProfile ?: return
        viewModelScope.launch {
            val result = friendsRepository.sendFriendRequest(myProfile, targetUser)
            result.onSuccess {
                _actionSuccess.value = "Friend request sent to @${targetUser.username}"
                executeSearch(_searchQuery.value)
            }.onFailure { err ->
                _actionError.value = "Failed to send request: ${err.message}"
            }
        }
    }

    fun acceptFriendRequest(request: FriendRequest) {
        val myProfile = currentProfile ?: return
        viewModelScope.launch {
            val result = friendsRepository.acceptFriendRequest(myProfile, request)
            result.onSuccess {
                _actionSuccess.value = "You and ${request.senderDisplayName} are now friends!"
                executeSearch(_searchQuery.value)
            }.onFailure { err ->
                _actionError.value = "Failed to accept request: ${err.message}"
            }
        }
    }

    fun rejectFriendRequest(request: FriendRequest) {
        val myProfile = currentProfile ?: return
        viewModelScope.launch {
            val result = friendsRepository.rejectFriendRequest(myProfile.uid, request)
            result.onSuccess {
                _actionSuccess.value = "Request declined"
                executeSearch(_searchQuery.value)
            }.onFailure { err ->
                _actionError.value = "Failed to decline: ${err.message}"
            }
        }
    }

    fun cancelOutgoingRequest(receiverUid: String) {
        val myProfile = currentProfile ?: return
        viewModelScope.launch {
            val result = friendsRepository.cancelFriendRequest(myProfile.uid, receiverUid)
            result.onSuccess {
                _actionSuccess.value = "Friend request cancelled"
                executeSearch(_searchQuery.value)
            }.onFailure { err ->
                _actionError.value = "Failed to cancel request: ${err.message}"
            }
        }
    }

    fun removeFriend(friendUid: String, friendName: String) {
        val myProfile = currentProfile ?: return
        viewModelScope.launch {
            val result = friendsRepository.removeFriend(myProfile.uid, friendUid)
            result.onSuccess {
                _actionSuccess.value = "Removed $friendName from friends"
                executeSearch(_searchQuery.value)
            }.onFailure { err ->
                _actionError.value = "Failed to unfriend: ${err.message}"
            }
        }
    }

    fun clearFeedback() {
        _actionError.value = null
        _actionSuccess.value = null
    }

    override fun onCleared() {
        super.onCleared()
        currentProfile?.let { friendsRepository.clearPresence(it.uid) }
    }
}
