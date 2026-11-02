package com.xingshi.ai.database.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class MessageType {
    @SerialName("text") TEXT,
    @SerialName("image") IMAGE,
    @SerialName("audio") AUDIO,
    @SerialName("video") VIDEO,
    @SerialName("voice") VOICE,
    @SerialName("file") FILE,
    /** 通话记录卡：仅展示开始/结束时间与有效时长，不作为一句发言入模。 */
    @SerialName("call") CALL
}
