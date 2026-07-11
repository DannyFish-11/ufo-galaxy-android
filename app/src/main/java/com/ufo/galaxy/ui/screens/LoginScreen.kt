package com.ufo.galaxy.ui.screens

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ufo.galaxy.auth.OAuthUser
import com.ufo.galaxy.auth.OAuthViewModel
import com.ufo.galaxy.auth.OAuthUiState
import com.ufo.galaxy.ui.theme.UFOGalaxyTheme

/**
 * UFO Galaxy 登录界面。
 *
 * 提供 Google 和 GitHub 两种 OAuth 登录方式，以及跳过登录的本地模式选项。
 * 界面风格与 UFO Galaxy 书法卷轴主题保持一致。
 *
 * ## 使用方式
 * ```kotlin
 * // 在 MainActivity 或其他 Activity 中
 * setContent {
 *     UFOGalaxyTheme {
 *         LoginScreen(
 *             onLoginSuccess = { user ->
 *                 // 登录成功，跳转到首页
 *                 navigateToHomeScreen(user)
 *             },
 *             onSkipLogin = {
 *                 // 用户选择跳过登录，进入本地模式
 *                 navigateToHomeScreenLocalMode()
 *             }
 *         )
 *     }
 * }
 * ```
 *
 * @param viewModel      OAuth ViewModel
 * @param onLoginSuccess 登录成功回调，参数为登录用户信息
 * @param onSkipLogin    跳过登录回调（本地模式）
 */
@Composable
fun LoginScreen(
    viewModel: OAuthViewModel = viewModel(),
    onLoginSuccess: (OAuthUser) -> Unit = {},
    onSkipLogin: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // 启动时检查已有登录
    LaunchedEffect(Unit) {
        viewModel.checkExistingLogin()
    }

    // 监听状态变化
    LaunchedEffect(uiState) {
        when (val state = uiState) {
            is OAuthUiState.Success -> {
                onLoginSuccess(state.user)
            }
            is OAuthUiState.Error -> {
                snackbarHostState.showSnackbar(
                    message = state.message,
                    duration = SnackbarDuration.Long
                )
                // 显示后重置状态
                viewModel.resetState()
            }
            else -> { /* Idle / Loading 无需特殊处理 */ }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { paddingValues ->
        LoginScreenContent(
            uiState = uiState,
            paddingValues = paddingValues,
            onGoogleSignIn = {
                viewModel.signInWithGoogle(context as Activity)
            },
            onGitHubSignIn = {
                viewModel.signInWithGitHub(context as Activity)
            },
            onSkipLogin = onSkipLogin
        )
    }
}

/**
 * 登录界面内容 Composable。
 */
@Composable
private fun LoginScreenContent(
    uiState: OAuthUiState,
    paddingValues: PaddingValues,
    onGoogleSignIn: () -> Unit,
    onGitHubSignIn: () -> Unit,
    onSkipLogin: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // ── Logo 区域 ─────────────────────────────────────────────────
            LoginLogoSection()

            Spacer(modifier = Modifier.height(40.dp))

            // ── 标题区域 ─────────────────────────────────────────────────
            Text(
                text = "登录到 UFO Galaxy",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Text(
                text = "选择一种方式开始您的智能助手之旅",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp)
            )

            Spacer(modifier = Modifier.height(48.dp))

            // ── 登录按钮区域 ─────────────────────────────────────────────
            LoginButtonSection(
                isLoading = uiState is OAuthUiState.Loading,
                onGoogleSignIn = onGoogleSignIn,
                onGitHubSignIn = onGitHubSignIn
            )

            Spacer(modifier = Modifier.height(32.dp))

            // ── 跳过登录 ─────────────────────────────────────────────────
            TextButton(
                onClick = onSkipLogin,
                enabled = uiState !is OAuthUiState.Loading
            ) {
                Text(
                    text = "跳过登录（本地模式）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            // ── 加载中遮罩 ───────────────────────────────────────────────
            AnimatedVisibility(
                visible = uiState is OAuthUiState.Loading,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(top = 16.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(36.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 3.dp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "登录中...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Logo 展示区域。
 * 居中显示 UFO Galaxy 应用 Logo 和名称。
 */
@Composable
private fun LoginLogoSection() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Logo 圆形背景
        Box(
            modifier = Modifier
                .size(100.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            // UFO Galaxy Logo - 使用首字母 "U" 作为 Logo
            Text(
                text = "U",
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 48.sp
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 应用名称
        Text(
            text = "UFO Galaxy",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.sp
        )

        // 副标题
        Text(
            text = "智能助手",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
            letterSpacing = 4.sp
        )
    }
}

/**
 * 登录按钮区域。
 * 包含 Google 和 GitHub 两种登录按钮。
 */
@Composable
private fun LoginButtonSection(
    isLoading: Boolean,
    onGoogleSignIn: () -> Unit,
    onGitHubSignIn: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Google 登录按钮 ──
        // 白色背景，Google Logo，黑色文字
        OutlinedButton(
            onClick = onGoogleSignIn,
            enabled = !isLoading,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, Color(0xFFDADCE0)),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = Color.White,
                contentColor = Color(0xFF3C4043),
                disabledContainerColor = Color(0xFFF5F5F5),
                disabledContentColor = Color(0xFFBDC1C6)
            )
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                // Google "G" Logo - 使用 Canvas 绘制
                GoogleLogo(
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "使用 Google 登录",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // ── GitHub 登录按钮 ──
        // 黑色背景，GitHub Logo（猫头鹰），白色文字
        Button(
            onClick = onGitHubSignIn,
            enabled = !isLoading,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF24292F),
                contentColor = Color.White,
                disabledContainerColor = Color(0xFF8C959F),
                disabledContentColor = Color(0xFFD0D7DE)
            )
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                // GitHub Logo - 使用矢量图标
                GitHubLogo(
                    modifier = Modifier.size(20.dp),
                    tint = Color.White
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "使用 GitHub 登录",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

/**
 * Google "G" Logo Composable。
 * 使用四个彩色圆弧组合绘制 Google 标志性 "G"。
 */
@Composable
private fun GoogleLogo(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val centerX = size.width / 2
        val centerY = size.height / 2
        val radius = (size.width.coerceAtMost(size.height) / 2) * 0.85f
        val strokeWidth = radius * 0.22f
        val topLeft = Offset(centerX - radius, centerY - radius)
        val arcSize = Size(radius * 2, radius * 2)
        val stroke = Stroke(width = strokeWidth)

        // 蓝色圆弧（底部右侧）
        drawArc(
            color = Color(0xFF4285F4),
            startAngle = 0f,
            sweepAngle = 110f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = stroke
        )

        // 红色圆弧（右侧）
        drawArc(
            color = Color(0xFFEA4335),
            startAngle = 100f,
            sweepAngle = 70f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = stroke
        )

        // 黄色圆弧（左下）
        drawArc(
            color = Color(0xFFFBBC05),
            startAngle = 190f,
            sweepAngle = 70f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = stroke
        )

        // 绿色圆弧（左上到右上）
        drawArc(
            color = Color(0xFF34A853),
            startAngle = 270f,
            sweepAngle = 80f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = stroke
        )
    }
}

/**
 * GitHub Octocat Logo Composable。
 * 使用矢量路径绘制 GitHub 猫头鹰图标。
 */
@Composable
private fun GitHubLogo(
    modifier: Modifier = Modifier,
    tint: Color = Color.White
) {
    // 使用预解析的 SVG Path 数据绘制 GitHub Octocat
    // 路径基于 24x24 的视口，通过 Canvas 缩放适配目标尺寸
    val pathNodes = remember {
        try {
            PathParser().parsePathString(
                "M12,2C6.477,2,2,6.477,2,12c0,4.42,2.87,8.17,6.84,9.5 " +
                    "c0.5,0.08,0.66-0.23,0.66-0.5v-1.69c-2.77,0.6-3.36-1.34-3.36-1.34 " +
                    "c-0.45-1.15-1.11-1.46-1.11-1.46c-0.91-0.62,0.07-0.6,0.07-0.6 " +
                    "c1,0.07,1.53,1.03,1.53,1.03c0.89,1.52,2.34,1.08,2.91,0.83 " +
                    "c0.09-0.65,0.35-1.09,0.63-1.34c-2.22-0.25-4.55-1.11-4.55-4.92 " +
                    "c0-1.11,0.38-2,1.03-2.71c-0.1-0.25-0.45-1.29,0.1-2.64 " +
                    "c0,0,0.84-0.27,2.75,1.02c0.79-0.22,1.65-0.33,2.5-0.33 " +
                    "c0.85,0,1.71,0.11,2.5,0.33c1.91-1.29,2.75-1.02,2.75-1.02 " +
                    "c0.55,1.35,0.2,2.39,0.1,2.64c0.65,0.71,1.03,1.6,1.03,2.71 " +
                    "c0,3.82-2.34,4.66-4.57,4.91c0.36,0.31,0.69,0.92,0.69,1.85 " +
                    "V21c0,0.27,0.16,0.59,0.67,0.5C19.14,20.16,22,16.42,22,12 " +
                    "C22,6.477,17.523,2,12,2z"
            )
        } catch (e: Exception) {
            PathParser().parsePathString("")
        }
    }

    Canvas(modifier = modifier) {
        val scaleFactor = size.width.coerceAtMost(size.height) / 24f
        drawContext.canvas.save()
        drawContext.canvas.scale(scaleFactor, scaleFactor)
        val path = pathNodes.toPath()
        drawPath(
            path = path,
            color = tint
        )
        drawContext.canvas.restore()
    }
}

// ── 预览 ──────────────────────────────────────────────────────────────────

@Preview(showBackground = true)
@Composable
private fun LoginScreenPreview() {
    UFOGalaxyTheme {
        LoginScreenContent(
            uiState = OAuthUiState.Idle,
            paddingValues = PaddingValues(),
            onGoogleSignIn = {},
            onGitHubSignIn = {},
            onSkipLogin = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun LoginScreenLoadingPreview() {
    UFOGalaxyTheme {
        LoginScreenContent(
            uiState = OAuthUiState.Loading,
            paddingValues = PaddingValues(),
            onGoogleSignIn = {},
            onGitHubSignIn = {},
            onSkipLogin = {}
        )
    }
}
