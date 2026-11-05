package com.xingshi.ai.feature.settings.ui.screen
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xingshi.ai.database.model.ApiProvider
import com.xingshi.ai.themes.official.R

@Composable
fun ProviderLogo(
    provider: ApiProvider,
    size: androidx.compose.ui.unit.Dp = 32.dp,
    cornerRadius: androidx.compose.ui.unit.Dp = 8.dp
) {
    val logoRes = when (provider) {
        ApiProvider.OPENAI -> R.drawable.logo_openai
        ApiProvider.ANTHROPIC -> R.drawable.logo_anthropic
        ApiProvider.GEMINI -> R.drawable.logo_gemini
        ApiProvider.DEEPSEEK -> R.drawable.logo_deepseek
        ApiProvider.DASHSCOPE -> R.drawable.logo_qwen
        ApiProvider.KIMI -> R.drawable.logo_kimi
        ApiProvider.XIAOMI -> R.drawable.logo_xiaomi
        ApiProvider.ZHIPU -> R.drawable.logo_zhipu
        ApiProvider.SILICONFLOW -> R.drawable.logo_siliconflow
        ApiProvider.OPENROUTER -> R.drawable.logo_openrouter
        ApiProvider.GROQ -> R.drawable.logo_groq
        else -> null
    }

    if (logoRes != null) {
        Image(
            painter = painterResource(id = logoRes),
            contentDescription = provider.displayName,
            modifier = Modifier.size(size),
            contentScale = ContentScale.Fit
        )
    } else {
        val (text, bgColor, textColor) = when (provider) {
            ApiProvider.PARTNER -> Triple("C", Color(0xFF3F8CFF), Color.White)
            ApiProvider.IFLYTEK -> Triple("讯", Color(0xFF1677FF), Color.White)
            ApiProvider.CUSTOM -> Triple("?", Color(0xFF888888).copy(alpha = 0.15f), Color(0xFF888888))
            else -> Triple("?", Color(0xFF888888).copy(alpha = 0.15f), Color(0xFF888888))
        }

        Box(
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(cornerRadius))
                .background(bgColor),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                fontSize = if (text.length > 1) (size.value * 0.35).sp else (size.value * 0.45).sp,
                fontWeight = FontWeight.Bold,
                color = textColor
            )
        }
    }
}

private fun providerInitials(provider: ApiProvider): Triple<String, Color, Color> {
    return when (provider) {
        ApiProvider.OPENAI -> Triple("O", Color(0xFF10A37F), Color.White)
        ApiProvider.ANTHROPIC -> Triple("C", Color(0xFFCC785C), Color.White)
        ApiProvider.GEMINI -> Triple("G", Color(0xFF4285F4), Color.White)
        ApiProvider.DEEPSEEK -> Triple("D", Color(0xFF4D6BFA), Color.White)
        ApiProvider.DASHSCOPE -> Triple("通", Color(0xFF615CED), Color.White)
        ApiProvider.KIMI -> Triple("K", Color(0xFF10A37F), Color.White)
        ApiProvider.XIAOMI -> Triple("米", Color(0xFFFF6900), Color.White)
        ApiProvider.MINIMAX -> Triple("M", Color(0xFFF23F5D), Color.White)
        ApiProvider.ELEVENLABS -> Triple("E", Color(0xFF111111), Color.White)
        ApiProvider.ALIYUN -> Triple("阿", Color(0xFFFF6A00), Color.White)
        ApiProvider.IFLYTEK -> Triple("讯", Color(0xFF1677FF), Color.White)
        ApiProvider.ZHIPU -> Triple("智", Color(0xFF4169E1), Color.White)
        ApiProvider.SILICONFLOW -> Triple("硅", Color(0xFF10A37F), Color.White)
        ApiProvider.OPENROUTER -> Triple("OR", Color(0xFF7B68EE), Color.White)
        ApiProvider.GROQ -> Triple("G", Color(0xFFF4845F), Color.White)
        ApiProvider.PARTNER -> Triple("C", Color(0xFF3F8CFF), Color.White)
        ApiProvider.CUSTOM -> Triple("?", Color(0xFF888888).copy(alpha = 0.15f), Color(0xFF888888))
    }
}
