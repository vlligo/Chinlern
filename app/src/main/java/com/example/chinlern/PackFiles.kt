package com.example.chinlern

import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/** Reads provider streams without relying on filesystem paths, MIME types or reported sizes. */
object PackFiles {
    const val MAX_FILES = 100
    const val MAX_TOTAL_BYTES = 20_000_000
    private const val MAX_FILE_BYTES = 2_000_000
    data class Item(val source: String, val preview: PackImport.Preview)
    data class Result(val items: List<Item>, val errors: List<String>)
    class Budget {
        var bytes = 0
        var entries = 0
    }
    fun read(name: String, input: InputStream, budget: Budget = Budget()): Result {
        val items = mutableListOf<Item>()
        val errors = mutableListOf<String>()
        fun parse(source: String, bytes: ByteArray) {
            try {
                val preview = PackImport.parse(bytes.toString(Charsets.UTF_8))
                require(preview.name.isNotBlank()) { "Название набора пустое." }
                items += Item(source, preview)
            } catch (e: Exception) { errors += "$source: ${e.message ?: "Неверный JSON"}" }
        }
        try {
            val stream = input.buffered()
            stream.mark(4)
            val header = ByteArray(4)
            var count = 0
            while (count < header.size) {
                val next = stream.read(header, count, header.size - count)
                if (next < 0) break
                count += next
            }
            stream.reset()
            val zip = name.endsWith(".zip", true) || (count >= 2 && header[0] == 80.toByte() && header[1] == 75.toByte())
            if (!zip) {
                require(++budget.entries <= MAX_FILES) { "Не более $MAX_FILES файлов за один импорт." }
                parse(name, boundedRead(stream, MAX_FILE_BYTES, budget))
            } else {
                // Entries are only read in memory, never extracted onto the filesystem.
                ZipInputStream(stream).use { archive ->
                    while (true) {
                        val entry = archive.nextEntry ?: break
                        require(++budget.entries <= MAX_FILES) { "Не более $MAX_FILES записей за один импорт." }
                        val bytes = boundedRead(archive, MAX_FILE_BYTES, budget)
                        if (!entry.isDirectory && !entry.name.startsWith("__MACOSX/") &&
                            (entry.name.endsWith(".json", true) || entry.name.endsWith(".txt", true))) {
                            parse("$name / ${entry.name}", bytes)
                        }
                        archive.closeEntry()
                    }
                }
                if (items.isEmpty() && errors.isEmpty()) errors += "$name: в ZIP нет наборов .json или .txt."
            }
        } catch (e: Exception) { errors += "$name: ${e.message ?: "Не удалось прочитать файл"}" }
        return Result(items, errors)
    }
    private fun boundedRead(input: InputStream, limit: Int, budget: Budget): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            budget.bytes += count
            require(budget.bytes <= MAX_TOTAL_BYTES) { "Общий размер распакованных данных превышает 20 МБ." }
            require(output.size() + count <= limit) { "Файл слишком большой (максимум 2 МБ)." }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
