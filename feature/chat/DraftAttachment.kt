package com.xingshi.ai.feature.chat.ui.viewmodel

enum class DraftAttachmentKind { IMAGE, DOCUMENT, VIDEO, BILIBILI }

data class DraftAttachment(
    val id: String,
    val path: String,
    val fileName: String,
    val mimeType: String,
    val kind: DraftAttachmentKind,
    val sourceUrl: String = ""
)
