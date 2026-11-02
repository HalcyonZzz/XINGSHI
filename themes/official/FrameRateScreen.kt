package com.xingshi.ai.feature.settings.ui.screen

import com.xingshi.ai.theme.framework.rememberThemeState
import com.xingshi.ai.theme.framework.ThemeRuntime
import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.res.stringResource
import com.xingshi.ai.themes.official.R
import com.xingshi.ai.theme.framework.FrameRateController
import androidx.compose.foundation.isSystemInDarkTheme

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import com.xingshi.ai.theme.framework.themeText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FrameRateScreen(
    onNavigateBack: () -> Unit,
    activity: Activity
) {
    val context = LocalContext.current
    val themeState = rememberThemeState()
    val appearanceContext = LocalContext.current
    val isDark = remember(themeState.revision) { ThemeRuntime.appearance(appearanceContext).isDark }
    val colorScheme = MaterialTheme.colorScheme
    val bgColor = colorScheme.background
    val cardColor = colorScheme.surfaceVariant
    val textPrimary = colorScheme.onSurface
    val textSecondary = colorScheme.onSurfaceVariant
    val dividerColor = colorScheme.outline

    val supportedRates = remember { FrameRateController.getSupportedFrameRates(context) }
    var selectedRate by remember {
        mutableStateOf(FrameRateController.getSavedFrameRate(context))
    }

    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.statusBars)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(cardColor, shape = RoundedCornerShape(20.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = themeText("cd_back"),
                            tint = textPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Text(
                        text = themeText("framerate_title"),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp
                        ),
                        color = textPrimary
                    )

                    Spacer(modifier = Modifier.width(32.dp))
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(bgColor)
                .padding(
                    top = paddingValues.calculateTopPadding(),
                    start = 16.dp,
                    end = 16.dp,
                    bottom = 16.dp
                )
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = themeText("screen_refresh_rate"),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 13.sp
                ),
                color = textSecondary,
                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(cardColor)
            ) {
                supportedRates.forEachIndexed { index, rate ->
                    FrameRateOptionItem(
                        rate = rate,
                        isSelected = selectedRate == rate,
                        textPrimary = textPrimary,
                        textSecondary = textSecondary,
                        onClick = {
                            selectedRate = rate
                            FrameRateController.saveFrameRate(context, rate)
                            FrameRateController.applyFrameRate(activity.window, rate)
                        }
                    )
                    if (index < supportedRates.size - 1) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 16.dp),
                            thickness = 0.5.dp,
                            color = dividerColor
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = themeText("framerate_hint"),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 12.sp
                ),
                color = textSecondary,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
    }
}

@Composable
private fun FrameRateOptionItem(
    rate: FrameRateController.FrameRate,
    isSelected: Boolean,
    textPrimary: Color,
    textSecondary: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = if (rate == FrameRateController.FrameRate.AUTO) themeText("framerate_auto") else rate.label,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 16.sp
            ),
            color = textPrimary
        )

        if (isSelected) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = themeText("cd_selected"),
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}
