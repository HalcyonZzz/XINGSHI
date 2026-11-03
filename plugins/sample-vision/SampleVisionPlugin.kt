package com.xingshi.ai.plugins.samplevision

import android.net.Uri
import androidx.compose.material3.Text
import androidx.compose.ui.unit.sp
import com.xingshi.ai.plugins.api.PluginChatCard
import com.xingshi.ai.plugins.api.PluginReport
import com.xingshi.ai.plugins.api.PluginRequest
import com.xingshi.ai.plugins.api.PluginRuntime
import com.xingshi.ai.plugins.api.VisionAnalysisPlugin
import com.xingshi.ai.plugins.api.VisionAnalysisRequest
import com.xingshi.ai.plugins.api.VisionAnalysisResult
import com.xingshi.ai.plugins.api.XingShiPlugin

class SampleVisionPlugin : XingShiPlugin, VisionAnalysisPlugin {

    override val id: String = "sample-vision"
    override val name: String = "视觉识别"

    override fun acceptsSentImages(): Boolean = true

    override fun requiresVisionModel(): Boolean = true

    override fun chatCards(): List<PluginChatCard> = listOf(
        PluginChatCard("sample-vision-card") { context ->
            val text = (context.report as? PluginReport.Success)?.text ?: "发一张图片即可自动识别"
            Text(text = "【vision】$text", fontSize = 12.sp)
        }
    )

    override suspend fun analyze(request: VisionAnalysisRequest): VisionAnalysisResult =
        VisionAnalysisClient.analyze(request)

     
    override suspend fun run(request: PluginRequest, runtime: PluginRuntime): PluginReport {
        val path = request.attachments.firstOrNull { it.kind.name == "IMAGE" }?.path
            ?: return PluginReport.Failure("没有收到图片")
        if (path.isBlank()) return PluginReport.Failure("没有收到图片")
        
        return PluginReport.Failure("缺少视觉模型配置，无法识别图片「${Uri.decode(path).substringAfterLast('/')}」")
    }
}
