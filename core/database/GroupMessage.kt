package com.xingshi.ai.database.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Entity(
    tableName = "group_messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatGroup::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["groupId"]),
        Index(value = ["groupId", "timestamp"]),
        Index(value = ["groupId", "fileFormat"])
    ]
)
@Serializable
@SerialName("E1")
data class GroupMessage(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val groupId: Long,
    val companionId: Long,
     
    val content: String,
     
    val timestamp: Long = System.currentTimeMillis(),
     
    val searchContent: String = content,
     
    val fileFormat: FileFormat = FileFormat.TEXT,
     
    val linkString: String = "",
    val publishedAuthorName: String? = null,
    val backendLive: Boolean = false,
     
    val publishedAuthorStableId: String? = null,
    val publishedAuthorInstanceId: String? = null
)
