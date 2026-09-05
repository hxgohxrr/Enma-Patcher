package com.enmapatcher.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.res.stringResource
import com.enmapatcher.R
import kotlinx.coroutines.delay

private val DangerRed = Color(0xFFE53935)
private val DangerRedDark = Color(0xFFB71C1C)
private val DangerRedLight = Color(0xFFFF5252)
private val DangerBg = Color(0xFF2D0A0A)
private val DangerSurface = Color(0xFF1A0505)

private const val HOLD_DURATION_MS = 5000L

@Composable
fun SecurityWarningDialog(
    title: String,
    message: String,
    holdButtonText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var holdProgress by remember { mutableFloatStateOf(0f) }
    var isHolding by remember { mutableStateOf(false) }

    val infiniteTransition = rememberInfiniteTransition(label = "border_pulse")
    val borderAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "border_alpha",
    )

    LaunchedEffect(isHolding) {
        if (isHolding) {
            val startTime = System.currentTimeMillis()
            while (isHolding) {
                val elapsed = System.currentTimeMillis() - startTime
                holdProgress = (elapsed.toFloat() / HOLD_DURATION_MS).coerceIn(0f, 1f)
                if (holdProgress >= 1f) {
                    onConfirm()
                    break
                }
                delay(16L)
            }
        } else {
            holdProgress = 0f
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .border(
                    width = 2.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            DangerRed.copy(alpha = borderAlpha),
                            DangerRedLight.copy(alpha = borderAlpha * 0.6f),
                            DangerRed.copy(alpha = borderAlpha),
                        ),
                    ),
                    shape = RoundedCornerShape(20.dp),
                )
                .background(DangerSurface),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {

                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(32.dp))
                        .background(DangerRed.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = DangerRedLight,
                        modifier = Modifier.size(36.dp),
                    )
                }


                Text(
                    text = title,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = DangerRedLight,
                    textAlign = TextAlign.Center,
                )


                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(DangerRedDark.copy(alpha = 0.3f))
                        .padding(16.dp),
                ) {
                    Text(
                        text = message,
                        fontSize = 14.sp,
                        color = Color(0xFFFFCDD2),
                        lineHeight = 20.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(Modifier.height(4.dp))


                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(DangerBg)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    isHolding = true
                                    tryAwaitRelease()
                                    isHolding = false
                                },
                            )
                        },
                    contentAlignment = Alignment.CenterStart,
                ) {

                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(holdProgress)
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(DangerRedDark, DangerRed),
                                ),
                            ),
                    )

                    Text(
                        text = if (isHolding) {
                            val remaining = ((1f - holdProgress) * 5).toInt() + 1
                            "$holdButtonText (${remaining}s)"
                        } else holdButtonText,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                    )
                }


                TextButton(onClick = onDismiss) {
                    Text(
                        text = stringResource(R.string.cancel),
                        color = Color(0xFFEF9A9A),
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}
