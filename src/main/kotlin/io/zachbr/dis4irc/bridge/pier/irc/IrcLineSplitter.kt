/*
 * This file is part of Dis4IRC.
 *
 * Copyright (c) Dis4IRC contributors
 *
 * MIT License
 */

package io.zachbr.dis4irc.bridge.pier.irc

private const val MAX_CODE_POINT_BYTES = 4

/**
 * Splits text into parts that fit in an IRC line, measured in UTF-8 bytes as servers count them.
 *
 * Parts break between words where possible, like the IRC library's own word cutter. Words too long for a line are cut
 * between code points, so multi-byte characters and surrogate pairs are never split.
 */
object IrcLineSplitter {
    fun split(message: String, maxBytes: Int): List<String> {
        require(maxBytes >= MAX_CODE_POINT_BYTES) { "Line budget too small: $maxBytes" }
        if (utf8Length(message) <= maxBytes) {
            return listOf(message)
        }

        val parts = ArrayList<String>()
        val builder = StringBuilder()
        var builderBytes = 0
        for (originalWord in message.split(' ')) {
            var word = originalWord
            var wordBytes = utf8Length(word)
            val separatorBytes = if (builderBytes == 0) 0 else 1
            if (builderBytes + separatorBytes + wordBytes > maxBytes) {
                // a word too long for any line starts on the current one, if there is room
                if (wordBytes > maxBytes && builderBytes + separatorBytes < maxBytes) {
                    val head = takeBytes(word, maxBytes - builderBytes - separatorBytes)
                    if (head.isNotEmpty()) {
                        if (separatorBytes > 0) {
                            builder.append(' ')
                        }
                        builder.append(head)
                        word = word.substring(head.length)
                        wordBytes = utf8Length(word)
                    }
                }

                addPart(parts, builder)
                builder.setLength(0)
                builderBytes = 0
                while (wordBytes > maxBytes) {
                    val head = takeBytes(word, maxBytes)
                    parts.add(head)
                    word = word.substring(head.length)
                    wordBytes = utf8Length(word)
                }
            }

            if (builderBytes > 0) {
                builder.append(' ')
                builderBytes++
            }
            builder.append(word)
            builderBytes += wordBytes
        }
        addPart(parts, builder)

        // a last check, so no part can ever be cut off by the server
        return parts.flatMap { part -> if (utf8Length(part) <= maxBytes) listOf(part) else hardSplit(part, maxBytes) }
    }

    /**
     * Gets the number of bytes a string takes in UTF-8
     */
    fun utf8Length(text: CharSequence): Int {
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val codePoint = Character.codePointAt(text, i)
            bytes += codePointBytes(codePoint)
            i += Character.charCount(codePoint)
        }
        return bytes
    }

    private fun addPart(parts: MutableList<String>, builder: StringBuilder) {
        val part = builder.toString().trim(' ')
        if (part.isNotEmpty()) {
            parts.add(part)
        }
    }

    private fun hardSplit(text: String, maxBytes: Int): List<String> {
        val parts = ArrayList<String>()
        var rest = text
        while (rest.isNotEmpty()) {
            val head = takeBytes(rest, maxBytes)
            parts.add(head)
            rest = rest.substring(head.length)
        }
        return parts
    }

    /**
     * Gets the longest start of the text, in whole code points, that fits in the given number of bytes
     */
    private fun takeBytes(text: String, maxBytes: Int): String {
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val codePoint = text.codePointAt(i)
            bytes += codePointBytes(codePoint)
            if (bytes > maxBytes) {
                break
            }
            i += Character.charCount(codePoint)
        }
        return text.substring(0, i)
    }

    private fun codePointBytes(codePoint: Int): Int = when {
        codePoint < 0x80 -> 1
        codePoint < 0x800 -> 2
        codePoint < 0x10000 -> 3
        else -> 4
    }
}
