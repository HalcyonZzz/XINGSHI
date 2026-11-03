package com.xingshi.ai.common.text

 
object MessageSegmenter {

     
    enum class SplitMode {
         
        SIMPLE,
         
        GROUP
    }

    
    private val SPLIT_PARAGRAPH_REGEX = Regex("\\n{2,}")
    private val SPLIT_SENTENCE_REGEX = Regex("(?<=[。！？～…!?~])\\s*")

     
    fun split(text: String, mode: SplitMode = SplitMode.SIMPLE): List<String> {
        return when (mode) {
            SplitMode.SIMPLE -> splitSimple(text)
            SplitMode.GROUP -> splitGroup(text)
        }
    }

    
    private fun splitSimple(text: String): List<String> {
        val trimmed = text.trim()
        val paragraphs = trimmed.split(SPLIT_PARAGRAPH_REGEX).filter { it.isNotBlank() }
        if (paragraphs.size >= 2) return paragraphs.map { it.trim() }
        val sentences = trimmed.split(SPLIT_SENTENCE_REGEX).filter { it.isNotBlank() }
        if (sentences.size >= 2) return sentences.map { it.trim() }
        return listOf(trimmed)
    }

    
    private fun splitGroup(text: String): List<String> {
        val cleaned = text.trim().replace(Regex("\\n{2,}"), "\n")
        if (cleaned.length <= 15) return listOf(cleaned)

        val rawSegments = cleaned.split("\n").map { it.trim() }.filter { it.isNotBlank() }

        if (rawSegments.size > 1) {
            val result = mutableListOf<String>()
            for (segment in rawSegments) {
                if (segment.length <= 20) {
                    result.add(segment)
                } else {
                    result.addAll(splitLongSegment(segment))
                }
            }
            return result.ifEmpty { listOf(cleaned) }
        }

        return splitLongSegment(cleaned)
    }

    private fun splitLongSegment(text: String): List<String> {
        val sentences = mutableListOf<String>()
        val current = StringBuilder()

        fun flush() {
            val seg = current.toString().trim()
            if (seg.isNotEmpty()) sentences.add(seg)
            current.clear()
        }

        for (char in text) {
            current.append(char)
            when (char) {
                '。', '！', '？' -> flush()
                '…', '～' -> { if (current.length >= 3) flush() }
                ',', '，' -> {
                    if (current.length >= 10 && current.contains(Regex("[！？。]"))) {
                        flush()
                    }
                }
            }
        }
        flush()

        if (sentences.isEmpty()) return listOf(text)

        val result = mutableListOf<String>()
        var buffer = StringBuilder()

        for (sentence in sentences) {
            val cleanSentence = sentence.trimStart('，', ',', '.', '。', ' ')
            if (cleanSentence.isEmpty()) continue

            if (buffer.length + cleanSentence.length <= 12) {
                if (buffer.isNotEmpty()) buffer.append("，")
                buffer.append(cleanSentence)
            } else {
                if (buffer.isNotEmpty()) {
                    result.add(buffer.toString())
                    buffer = StringBuilder()
                }
                if (cleanSentence.length <= 14) {
                    buffer.append(cleanSentence)
                } else if (cleanSentence.length <= 25) {
                    result.add(cleanSentence)
                } else {
                    val midPoint = cleanSentence.length / 2
                    val startIndex = midPoint.coerceAtLeast(0)
                    val endIndex = (midPoint + 10).coerceAtMost(cleanSentence.length)
                    val searchRange = if (startIndex < endIndex) cleanSentence.substring(startIndex, endIndex) else ""
                    val splitPosInRange = searchRange.indexOfAny(charArrayOf('，', ',', '、'))
                    val splitPos = if (splitPosInRange >= 0) midPoint + splitPosInRange else -1
                    if (splitPos > 0) {
                        result.add(cleanSentence.take(splitPos + 1).trim())
                        buffer.append(cleanSentence.drop(splitPos + 1).trimStart())
                    } else {
                        result.add(cleanSentence.take(14).trimEnd('，', ','))
                        buffer.append(cleanSentence.drop(14).trimStart('，', ','))
                    }
                }
            }
        }
        if (buffer.isNotEmpty()) result.add(buffer.toString())

        return result.ifEmpty { listOf(text) }
    }
}
