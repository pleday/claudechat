package dev.claudechat

import com.google.gson.JsonParser
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

data class VideoInfo(val durationSec: Double, val width: Int, val height: Int, val hasAudio: Boolean)

/**
 * Pièces jointes vidéo. L'API n'accepte pas la vidéo : on en extrait quelques images (ffmpeg) envoyées comme images,
 * accompagnées d'un texte qui donne les instants. La piste audio n'est pas transmise.
 */
object VideoTools {
    val EXTENSIONS: Set<String> = Constants.videoExtensions
    val MAX_FRAMES: Int = Constants.maxVideoFrames
    val MAX_WIDTH: Int = Constants.maxVideoWidth

    fun isVideo(fileName: String) = fileName.substringAfterLast('.', "").lowercase() in EXTENSIONS

    /** Cherche un exécutable dans le PATH puis dans les emplacements usuels. */
    fun find(name: String): String? {
        val dirs = (System.getenv("PATH") ?: "").split(File.pathSeparatorChar) +
            listOf("/usr/bin", "/usr/local/bin", "/opt/homebrew/bin", "/snap/bin")
        return dirs.asSequence().filter { it.isNotBlank() }.map { File(it, name) }.firstOrNull { it.canExecute() }?.path
    }

    fun probe(path: String, ffprobe: String): VideoInfo? = runCatching {
        val p = ProcessBuilder(ffprobe, "-v", "error", "-print_format", "json", "-show_format", "-show_streams", path)
            .redirectErrorStream(false).start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(30, TimeUnit.SECONDS) || p.exitValue() != 0) return null
        val j = JsonParser.parseString(out).asJsonObject
        val streams = j["streams"]?.asJsonArray?.map { it.asJsonObject } ?: emptyList()
        val v = streams.firstOrNull { it["codec_type"]?.asString == "video" } ?: return null
        val dur = j["format"]?.asJsonObject?.get("duration")?.asString?.toDoubleOrNull()
            ?: v["duration"]?.asString?.toDoubleOrNull() ?: 0.0
        VideoInfo(dur, v["width"]?.asInt ?: 0, v["height"]?.asInt ?: 0, streams.any { it["codec_type"]?.asString == "audio" })
    }.getOrNull()

    /** Instants d'extraction : au milieu de segments égaux (au plus [MAX_FRAMES], environ une image toutes les 3 s). */
    fun frameTimes(durationSec: Double): List<Double> {
        if (durationSec < 1.0) return listOf(0.0)
        val n = minOf(MAX_FRAMES, maxOf(2, ceil(durationSec / 3.0).toInt()))
        return (0 until n).map { (it + 0.5) * durationSec / n }
    }

    /** Une image JPEG (réduite à [MAX_WIDTH] de large) à l'instant donné ; null si l'extraction échoue. */
    fun frameAt(path: String, seconds: Double, ffmpeg: String): ByteArray? = runCatching {
        val p = ProcessBuilder(
            ffmpeg, "-v", "error", "-ss", "%.3f".format(java.util.Locale.ROOT, seconds), "-i", path, "-frames:v", "1",
            "-vf", "scale='min($MAX_WIDTH,iw)':-2", "-q:v", "4", "-f", "image2", "-c:v", "mjpeg", "pipe:1",
        ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val bytes = p.inputStream.readBytes()
        if (!p.waitFor(30, TimeUnit.SECONDS)) { p.destroy(); return null }
        bytes.takeIf { it.size > 2 && it[0] == 0xFF.toByte() && it[1] == 0xD8.toByte() }
    }.getOrNull()

    fun fmt(sec: Double): String { val s = sec.toInt(); return "%d:%02d".format(s / 60, s % 60) }

    fun describe(rel: String, info: VideoInfo, times: List<Double>): String =
        "<video path=\"$rel\" duration=\"${fmt(info.durationSec)}\" resolution=\"${info.width}x${info.height}\" frames=\"${times.size}\" at=\"${times.joinToString(", ") { fmt(it) }}\">" +
            "Les images jointes sont extraites de cette vidéo aux instants indiqués, dans l'ordre." +
            (if (info.hasAudio) " La piste audio n'est pas transmise." else "") + "</video>"

    fun unavailable(rel: String, reason: String): String =
        "<video path=\"$rel\">Vidéo non analysée : $reason. Tu peux en extraire des images toi-même avec ffmpeg (outil Bash).</video>"

    /** Extrait les images et le texte descriptif ; en cas d'impossibilité, un texte explicatif sans image. */
    fun attach(path: String, rel: String, onProgress: (Int, Int) -> Unit = { _, _ -> }): Pair<String, List<ByteArray>> {
        val ffmpeg = find("ffmpeg"); val ffprobe = find("ffprobe")
        if (ffmpeg == null || ffprobe == null) return unavailable(rel, "ffmpeg est introuvable sur cette machine") to emptyList()
        val info = probe(path, ffprobe) ?: return unavailable(rel, "fichier vidéo illisible") to emptyList()
        val times = frameTimes(info.durationSec)
        val frames = ArrayList<ByteArray>()
        val kept = ArrayList<Double>()
        times.forEachIndexed { i, t ->
            onProgress(i, times.size)
            frameAt(path, t, ffmpeg)?.let { frames += it; kept += t }
        }
        if (frames.isEmpty()) return unavailable(rel, "aucune image n'a pu être extraite") to emptyList()
        return describe(rel, info, kept) to frames
    }
}
