package code.name.monkey.retromusic.lyrics

import android.content.Context
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** Online translation shared by the lyrics page and Now Playing. Source lyric files are never modified. */
object LyricsTranslation {
    private val languages = listOf(
        "English" to "en", "Indonesian" to "id", "Japanese" to "ja",
        "Korean" to "ko", "Chinese (Simplified)" to "zh-CN", "Spanish" to "es",
        "French" to "fr", "German" to "de", "Portuguese" to "pt",
        "Italian" to "it", "Thai" to "th", "Arabic" to "ar",
        "Russian" to "ru", "Turkish" to "tr", "Dutch" to "nl"
    )
    private val cache = ConcurrentHashMap<String, String>()

    fun showSettings(context: Context, source: String = "en", target: String = "id",
        onApply: (String, String) -> Unit) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 0)
        }
        val sourceSpinner = Spinner(context)
        val targetSpinner = Spinner(context)
        val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, languages.map { it.first })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        sourceSpinner.adapter = adapter
        targetSpinner.adapter = adapter
        sourceSpinner.setSelection(languages.indexOfFirst { it.second == source }.coerceAtLeast(0))
        targetSpinner.setSelection(languages.indexOfFirst { it.second == target }.coerceAtLeast(1))
        container.addView(TextView(context).apply { text = "Source language" })
        container.addView(sourceSpinner, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        container.addView(TextView(context).apply {
            text = "Translate to"
            setPadding(0, 16, 0, 0)
        })
        container.addView(targetSpinner, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        AlertDialog.Builder(context).setTitle("Translate lyrics").setView(container)
            .setPositiveButton("Activate") { _, _ ->
                val from = languages[sourceSpinner.selectedItemPosition].second
                val to = languages[targetSpinner.selectedItemPosition].second
                if (from != to) onApply(from, to)
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    /** Prefetches a complete set of rows in batches within the service byte limit. */
    @Throws(Exception::class)
    fun translateLines(lines: List<String>, source: String, target: String): List<String> {
        val result = MutableList(lines.size) { "" }
        var start = 0
        while (start < lines.size) {
            while (start < lines.size && lines[start].isBlank()) start++
            if (start >= lines.size) break
            var end = start
            var bytes = 0
            while (end < lines.size && lines[end].isNotBlank()) {
                val size = lines[end].toByteArray(Charsets.UTF_8).size
                if (size > 500) break
                val combined = bytes + size + if (end > start) 1 else 0
                if (combined > 500) break
                bytes = combined
                end++
            }
            if (end == start) end++
            val batch = lines.subList(start, end)
            val translated = if (batch.size == 1) {
                listOf(translateLongLine(batch[0], source, target))
            } else {
                runCatching {
                    val response = translate(batch.joinToString("\n"), source, target)
                    response.split("\n").map { it.trim() }.takeIf { it.size == batch.size }
                        ?: throw IllegalStateException("Translation service changed row boundaries")
                }.getOrElse {
                    batch.map { if (it.isBlank()) "" else translateLongLine(it, source, target) }
                }
            }
            translated.forEachIndexed { offset, value -> result[start + offset] = value }
            start = end
        }
        return result
    }

    private fun translateLongLine(line: String, source: String, target: String): String {
        if (line.toByteArray(Charsets.UTF_8).size <= 500) return translate(line, source, target)
        val chunks = mutableListOf<String>()
        var remaining = line.trim()
        while (remaining.isNotEmpty()) {
            var end = remaining.length
            while (end > 0 && remaining.substring(0, end).toByteArray(Charsets.UTF_8).size > 500) end--
            if (end < remaining.length) {
                val boundary = remaining.lastIndexOf(' ', end)
                if (boundary > 0) end = boundary
            }
            chunks += remaining.substring(0, end)
            remaining = remaining.substring(end).trimStart()
        }
        return chunks.joinToString(" ") { translate(it, source, target) }
    }

    @Throws(Exception::class)
    fun translate(text: String, source: String, target: String): String {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) return ""
        require(cleanText.toByteArray(Charsets.UTF_8).size <= 500) {
            "This lyric line exceeds the translation service's 500-byte limit."
        }
        val key = "$source|$target|$cleanText"
        cache[key]?.let { return it }
        val query = URLEncoder.encode(cleanText, "UTF-8")
        val pair = URLEncoder.encode("$source|$target", "UTF-8")
        val connection = (URL("https://api.mymemory.translated.net/get?q=$query&langpair=$pair")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 15000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "RSound-Android")
        }
        try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("Translation service returned HTTP ${connection.responseCode}")
            }
            val json = JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            if (json.optInt("responseStatus", 200) != 200) {
                throw IllegalStateException(json.optString("responseDetails", "Translation failed"))
            }
            val translated = json.optJSONObject("responseData")?.optString("translatedText").orEmpty()
                .replace("&#39;", "'").replace("&quot;", "\"").replace("&amp;", "&")
            if (translated.isBlank()) throw IllegalStateException("No translation was returned")
            cache[key] = translated
            return translated
        } finally {
            connection.disconnect()
        }
    }
}
