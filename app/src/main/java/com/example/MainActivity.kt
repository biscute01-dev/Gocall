package com.example

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.example.ui.screens.CallScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.RoseDestructive
import com.example.ui.theme.SlateDark950
import com.example.viewmodel.CallState
import com.example.viewmodel.CallViewModel

class MainActivity : ComponentActivity() {
    private val callViewModel: CallViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = SlateDark950
                ) {
                    MainApp(viewModel = callViewModel)
                }
            }
        }
    }
}

@Composable
fun MainApp(viewModel: CallViewModel) {
    val callState by viewModel.callState.collectAsState()
    var showEndCallConfirmDialog by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(callState) {
        when (val state = callState) {
            is CallState.Ended -> {
                // Return to home on call end
                viewModel.resetToIdle()
            }
            is CallState.Error -> {
                errorMessage = state.message
            }
            else -> {}
        }
    }

    val isInCall = callState !is CallState.Idle && callState !is CallState.Ended

    // Intercept back press when on active call
    BackHandler(enabled = isInCall) {
        showEndCallConfirmDialog = true
    }

    AnimatedContent(
        targetState = isInCall,
        transitionSpec = {
            fadeIn() togetherWith fadeOut()
        },
        label = "call_navigation"
    ) { inCall ->
        if (inCall) {
            CallScreen(
                viewModel = viewModel,
                onEndCall = {
                    viewModel.endCall()
                }
            )
        } else {
            HomeScreen(
                viewModel = viewModel,
                onCreateCall = { roomId ->
                    viewModel.createRoom(roomId)
                },
                onJoinCall = { roomId ->
                    viewModel.joinRoom(roomId)
                }
            )
        }
    }

    if (showEndCallConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showEndCallConfirmDialog = false },
            title = { Text("End Call?") },
            text = { Text("Are you sure you want to disconnect from this call?") },
            confirmButton = {
                Button(
                    onClick = {
                        showEndCallConfirmDialog = false
                        viewModel.endCall()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RoseDestructive)
                ) {
                    Text("End Call", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEndCallConfirmDialog = false }) {
                    Text("Stay in Call")
                }
            }
        )
    }

    if (errorMessage != null) {
        AlertDialog(
            onDismissRequest = {
                errorMessage = null
                viewModel.resetToIdle()
            },
            title = { Text("Call Error") },
            text = { Text(errorMessage ?: "An unexpected error occurred") },
            confirmButton = {
                Button(
                    onClick = {
                        errorMessage = null
                        viewModel.resetToIdle()
                    }
                ) {
                    Text("OK")
                }
            }
        )
    }
}
