package com.xingshi.ai.plugins.api

interface DocumentExtractionPlugin {

    suspend fun extract(
        request: DocumentExtractionRequest,
        runtime: DocumentExtractionRuntime
    ): DocumentExtractionResult
}

data class DocumentExtractionRequest(
    val path: String,
    val fileName: String,
    val mimeType: String
)

interface DocumentExtractionRuntime {
    fun isActive(): Boolean
}

sealed interface DocumentExtractionResult {

    data class Extracted(val format: DocumentFormat, val text: String) : DocumentExtractionResult

    data class Failed(val code: DocumentFailureCode) : DocumentExtractionResult

    data object Cancelled : DocumentExtractionResult
}

enum class DocumentFormat { MD, TXT, DOC, DOCX, XLS, XLSX }

enum class DocumentFailureCode { NOT_FOUND, UNSUPPORTED, CORRUPT, ENCRYPTED, EMPTY, TOO_LARGE, IO_ERROR }
