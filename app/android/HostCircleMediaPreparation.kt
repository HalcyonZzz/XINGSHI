package com.xingshi.ai

import android.app.Application
import com.xingshi.ai.database.AppDatabase
import com.xingshi.ai.database.repository.ApiConfigRepository
import com.xingshi.ai.domain.CircleAttachmentKind
import com.xingshi.ai.domain.CirclePublishMedia
import com.xingshi.ai.domain.ServiceRegistry
import com.xingshi.ai.feature.chat.VideoAnalysisService
import com.xingshi.ai.feature.chat.VisionAnalysisService
import com.xingshi.ai.feature.moments.CircleMediaPreparation
import com.xingshi.ai.feature.moments.CircleMediaPreparationProvider
import com.xingshi.ai.feature.moments.CirclePrepareResult
import com.xingshi.ai.feature.moments.CircleVideoSegment
import com.xingshi.ai.plugins.api.VideoProviderConfig
import java.io.File
import java.util.UUID

internal class HostCircleMediaPreparation(
    private val sliceRoot: File,
    private val vision: suspend (path: String, prompt: String) -> VisionAnalysisService.Outcome,
    private val videoConfig: suspend () -> VideoProviderConfig?,
    private val videoPrepare: suspend (
        path: String,
        fileName: String,
        mimeType: String,
        sliceDir: String,
        config: VideoProviderConfig,
        savedSegments: List<VideoAnalysisService.SegmentText>,
        onSegment: suspend (Int, VideoAnalysisService.SegmentText) -> Unit
    ) -> VideoAnalysisService.Outcome
) : CircleMediaPreparation {

    override suspend fun describe(media: CirclePublishMedia): CirclePrepareResult =
        describe(media, emptyList()) { }

    override suspend fun describe(
        media: CirclePublishMedia,
        saved: List<CircleVideoSegment>,
        onSegment: suspend (List<CircleVideoSegment>) -> Unit
    ): CirclePrepareResult = when (media.kind) {
        CircleAttachmentKind.IMAGE -> prepareImage(media)
        CircleAttachmentKind.VIDEO -> prepareVideo(media, saved, onSegment)
    }

    private suspend fun prepareImage(media: CirclePublishMedia): CirclePrepareResult {
        val outcome = vision(media.path, "")
        return if (outcome is VisionAnalysisService.Outcome.Described && outcome.text.isNotBlank()) {
            CirclePrepareResult.Described(outcome.text)
        } else {
            CirclePrepareResult.Failed(VISION_UNAVAILABLE)
        }
    }

    private suspend fun prepareVideo(
        media: CirclePublishMedia,
        saved: List<CircleVideoSegment>,
        onSegment: suspend (List<CircleVideoSegment>) -> Unit
    ): CirclePrepareResult {
        val config = videoConfig() ?: return CirclePrepareResult.Failed(VIDEO_UNAVAILABLE)
        val sliceDir = File(sliceRoot, UUID.randomUUID().toString())
        val sourceKey = VideoAnalysisService.fileKey(media.path)
        val endpoint = VideoAnalysisService.endpointOf(config.providerBaseUrl)
        val segments = saved.associateBy { it.startMs }.toMutableMap()
        return try {
            val outcome = videoPrepare(
                media.path,
                media.fileName,
                media.mimeType,
                sliceDir.absolutePath,
                config,
                saved.map {
                    VideoAnalysisService.SegmentText(
                        it.startMs, it.endMs, it.text, it.providerId, it.model, it.promptVersion, it.fileKey, it.endpoint
                    )
                }
            ) { _, segment ->
                segments[segment.startMs] = CircleVideoSegment(
                    segment.startMs, segment.endMs, segment.text,
                    providerId = config.providerId,
                    model = config.model,
                    promptVersion = VideoAnalysisService.PROMPT_VERSION,
                    fileKey = sourceKey,
                    endpoint = endpoint
                )
                onSegment(segments.values.sortedBy { it.startMs })
            }
            when {
                outcome is VideoAnalysisService.Outcome.Described &&
                    outcome.segments.isNotEmpty() &&
                    outcome.segments.all { it.text.isNotBlank() } ->
                    CirclePrepareResult.Described(VideoAnalysisService.videoText(outcome.segments))
                else -> CirclePrepareResult.Failed(VIDEO_UNAVAILABLE)
            }
        } finally {
            sliceDir.deleteRecursively()
        }
    }

    private companion object {
        const val VISION_UNAVAILABLE = "ui_circle_vision_unavailable"
        const val VIDEO_UNAVAILABLE = "ui_circle_video_unavailable"
    }
}

internal object CircleMediaPreparationRegistration {
    fun register(app: Application) {
        register(HostCircleMediaPreparationProvider(app))
    }

    internal fun register(provider: CircleMediaPreparationProvider) {
        if (ServiceRegistry.get(CircleMediaPreparationProvider::class.java) != null) return
        synchronized(this) {
            if (ServiceRegistry.get(CircleMediaPreparationProvider::class.java) != null) return
            ServiceRegistry.registerSingleton(CircleMediaPreparationProvider::class.java) { provider }
        }
    }
}

private class HostCircleMediaPreparationProvider(private val app: Application) : CircleMediaPreparationProvider {
    override fun create(): CircleMediaPreparation = HostCircleMediaPreparation(
        sliceRoot = File(app.cacheDir, "circle_video"),
        vision = { path, _ -> VisionAnalysisService.analyze(app, path, "") },
        videoConfig = {
            VideoAnalysisService.providerConfig(
                ApiConfigRepository(AppDatabase.getDatabase(app).apiConfigDao()).getVideoConfig()
            )
        },
        videoPrepare = { path, fileName, mimeType, sliceDir, config, savedSegments, onSegment ->
            VideoAnalysisService.prepare(
                path = path,
                fileName = fileName,
                mimeType = mimeType,
                sliceDir = sliceDir,
                config = config,
                savedSegments = savedSegments,
                onSegment = onSegment
            )
        }
    )
}
