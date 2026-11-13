package code.name.monkey.retromusic.network

import code.name.monkey.retromusic.lyrics.TtmlUtils
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.util.LyricUtil
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit

/** TimeLyr-inspired LRCLIB-first and LyricsPlus-fallback discovery. */
object OnlineLyricsRepository {
    sealed class DownloadResult {
        data class Saved(val format: String) : DownloadResult()
        object NotFound : DownloadResult()
        data class Failed(val message: String) : DownloadResult()
    }

    private data class Candidate(val json: JSONObject, val score: Double)

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    private val lyricsPlusMirrors = listOf(
        "lyricsplus.prjktla.my.id",
        "lyricsplus.atomix.one",
        "lyricsplus.binimum.org",
        "lyricsplus.prjktla.workers.dev",
        "lyricsplus-seven.vercel.app",
        "lyrics-plus-backend.vercel.app"
    )

    /** Blocking call; invoke only from Dispatchers.IO. */
    fun download(song: Song): DownloadResult {
        return try {
            downloadInternal(song)
        } catch (error: Exception) {
            DownloadResult.Failed(
                error.message ?: "Gagal menyimpan atau mengambil lirik."
            )
        }
    }

    private fun downloadInternal(song: Song): DownloadResult {
        if (song.title.isBlank() || song.artistName.isBlank()) return DownloadResult.NotFound

        var lrcContent: String? = null
        var networkFailure = false
        try {
            lrcContent = fetchLrclib(song)
        } catch (_: IOException) {
            networkFailure = true
        }

        var ttmlContent: String? = null
        try {
            ttmlContent = fetchLyricsPlus(song)
        } catch (_: IOException) {
            networkFailure = true
        }

        val lrcText = lrcContent?.trim().orEmpty()
        val hasLrcTimestamps = Regex("(?m)^\\s*\\[\\d{1,3}:\\d{2}(?:[.:]\\d{1,3})?\\]")
            .containsMatchIn(lrcText)
        val savedLrc = lrcText.isNotBlank() && hasLrcTimestamps &&
            LyricUtil.writeLrcToLoc(song.title, song.artistName, lrcText) != null
        val savedPlain = lrcText.isNotBlank() && !hasLrcTimestamps &&
            LyricUtil.writePlainLyricsToLoc(song.title, song.artistName, lrcText) != null

        var savedTtml = false
        if (!ttmlContent.isNullOrBlank() && TtmlUtils.isTtml(ttmlContent)) {
            val parsed = TtmlUtils.parseDocument(ttmlContent)
            if (parsed.getPlainLine(0L).isNotBlank()) {
                savedTtml = LyricUtil.writeTtmlToLoc(
                    song.title, song.artistName, ttmlContent
                ) != null
            }
        }

        if (savedTtml || savedLrc || savedPlain) {
            val formats = listOfNotNull(
                if (savedTtml) "TTML" else null,
                if (savedLrc) "LRC" else null,
                if (savedPlain) "plain text" else null
            )
            return DownloadResult.Saved(formats.joinToString(" + "))
        }
        return if (networkFailure) {
            DownloadResult.Failed("Tidak dapat terhubung ke layanan lirik. Periksa koneksi lalu coba lagi.")
        } else {
            DownloadResult.NotFound
        }
    }

    @Throws(IOException::class)
    private fun fetchLrclib(song: Song): String? {
        val durationSeconds = (song.duration / 1000L).coerceAtLeast(0L).toString()
        val exactUrl = HttpUrl.Builder()
            .scheme("https").host("lrclib.net").addPathSegments("api/get")
            .addQueryParameter("artist_name", song.artistName)
            .addQueryParameter("track_name", song.title)
            .addQueryParameter("album_name", song.albumName)
            .addQueryParameter("duration", durationSeconds)
            .build()

        val exact = try {
            getJson(exactUrl)
        } catch (_: IOException) {
            null
        }
        val exactLyrics = exact?.let { preferredLyrics(it) }
        if (!exactLyrics.isNullOrBlank()) return exactLyrics

        val query = listOf(song.artistName, song.title, song.albumName)
            .filter { it.isNotBlank() }.joinToString(" ")
        val searchUrl = HttpUrl.Builder()
            .scheme("https").host("lrclib.net").addPathSegments("api/search")
            .addQueryParameter("q", query).build()

        val body = getText(searchUrl) ?: return null
        val results = try { JSONArray(body) } catch (_: Exception) { return null }
        val ranked = ArrayList<Candidate>()
        for (index in 0 until results.length()) {
            val item = results.optJSONObject(index) ?: continue
            ranked.add(Candidate(item, rank(item, song)))
        }
        ranked.sortByDescending { it.score }
        val best = ranked.firstOrNull() ?: return null
        val secondScore = ranked.getOrNull(1)?.score ?: 0.0
        if (best.score < 0.72 || best.score - secondScore < 0.08) return null
        return preferredLyrics(best.json)
    }

    private fun preferredLyrics(item: JSONObject): String? {
        val synced = item.optString("syncedLyrics").trim()
        if (synced.isNotEmpty() && synced != "null") return synced
        val plain = item.optString("plainLyrics").trim()
        return plain.takeIf { it.isNotEmpty() && it != "null" }
    }

    private fun rank(item: JSONObject, song: Song): Double {
        val title = normalize(song.title)
        val artist = normalize(song.artistName.replace(Regex("(?i)\\b(feat|ft)\\.?[^,;]*"), ""))
        val album = normalize(song.albumName)
        val candidateTitle = normalize(item.optString("trackName"))
        val candidateArtist = normalize(item.optString("artistName"))
        val candidateAlbum = normalize(item.optString("albumName"))

        var score = fieldScore(title, candidateTitle, 0.55, 0.35, 0.40)
        score += fieldScore(artist, candidateArtist, 0.35, 0.20, 0.25)
        score += fieldScore(album, candidateAlbum, 0.10, 0.04, 0.06)

        val candidateDuration = item.optDouble("duration", 0.0)
        val duration = song.duration / 1000.0
        if (duration > 0 && candidateDuration > 0) {
            val difference = kotlin.math.abs(duration - candidateDuration)
            score += when {
                difference <= 2 -> 0.20
                difference <= 6 -> 0.12
                difference <= 12 -> 0.06
                else -> -0.15
            }
        }
        return score
    }

    private fun fieldScore(
        source: String,
        target: String,
        exact: Double,
        partial: Double,
        overlapWeight: Double
    ): Double {
        if (source.isEmpty() || target.isEmpty()) return 0.0
        if (source == target) return exact
        if (source.contains(target) || target.contains(source)) return partial
        val a = source.split(" ").filter { it.isNotBlank() }.toSet()
        val b = target.split(" ").filter { it.isNotBlank() }.toSet()
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return a.intersect(b).size.toDouble() / a.union(b).size.toDouble() * overlapWeight
    }

    private fun normalize(value: String): String {
        val ascii = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
        return ascii.replace("[^a-z0-9\\s]".toRegex(), " ")
            .replace("\\s+".toRegex(), " ").trim()
    }

    @Throws(IOException::class)
    private fun fetchLyricsPlus(song: Song): String? {
        val duration = (song.duration / 1000L).coerceAtLeast(0L).toString()
        var lastNetworkFailure: IOException? = null
        for (host in lyricsPlusMirrors) {
            val querySets = listOf(
                listOf("title" to song.title, "artist" to song.artistName,
                    "album" to song.albumName, "duration" to duration),
                listOf("track_name" to song.title, "artist_name" to song.artistName,
                    "album_name" to song.albumName, "duration" to duration)
            )
            for (query in querySets) {
                val builder = HttpUrl.Builder().scheme("https").host(host)
                    .addPathSegments("v1/ttml/get")
                query.forEach { (key, value) -> builder.addQueryParameter(key, value) }
                val body = try {
                    getText(builder.build())
                } catch (error: IOException) {
                    lastNetworkFailure = error
                    null
                } ?: continue
                val ttml = extractTtml(body)
                if (!ttml.isNullOrBlank() && TtmlUtils.isTtml(ttml)) return ttml
            }
        }
        if (lastNetworkFailure != null) throw lastNetworkFailure
        return null
    }

    private fun extractTtml(body: String): String? {
        val value = body.trim()
        if (TtmlUtils.isTtml(value)) return value
        return try {
            JSONObject(value).optString("ttml").takeIf { TtmlUtils.isTtml(it) }
        } catch (_: Exception) {
            null
        }
    }

    @Throws(IOException::class)
    private fun getJson(url: HttpUrl): JSONObject? {
        val body = getText(url) ?: return null
        return try { JSONObject(body) } catch (_: Exception) { null }
    }

    @Throws(IOException::class)
    private fun getText(url: HttpUrl): String? {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json, application/ttml+xml, application/xml, text/xml")
            .header("User-Agent", "RetroMusic")
            .get().build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404 || response.code == 400) return null
            if (response.code == 429 || response.code >= 500) {
                throw IOException("Lyrics service temporarily unavailable (" + response.code + ")")
            }
            if (!response.isSuccessful) return null
            return response.body?.string()?.takeIf { it.isNotBlank() && it != "{}" }
        }
    }
}
