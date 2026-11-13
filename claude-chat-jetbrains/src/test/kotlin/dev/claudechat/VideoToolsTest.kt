package dev.claudechat

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class VideoToolsTest {
    @TempDir lateinit var tmp: Path

    @Test fun recognisesVideoExtensions() {
        assertTrue(VideoTools.isVideo("clip.MP4")); assertTrue(VideoTools.isVideo("a.b.webm")); assertTrue(VideoTools.isVideo("x.mov"))
        assertFalse(VideoTools.isVideo("photo.png")); assertFalse(VideoTools.isVideo("mp4")); assertFalse(VideoTools.isVideo("notes.txt"))
    }

    @Test fun frameTimesAreEvenAndBounded() {
        assertEquals(listOf(0.0), VideoTools.frameTimes(0.2))
        assertEquals(2, VideoTools.frameTimes(2.0).size)
        val t = VideoTools.frameTimes(60.0)
        assertEquals(10, t.size, "plafonné")
        assertTrue(t.zipWithNext().all { (a, b) -> b > a } && t.first() > 0.0 && t.last() < 60.0)
        assertEquals(10, VideoTools.frameTimes(7200.0).size)
    }

    @Test fun formatsTimestamps() { assertEquals("0:05", VideoTools.fmt(5.4)); assertEquals("2:03", VideoTools.fmt(123.0)) }

    @Test fun descriptionMentionsAudioOnlyWhenPresent() {
        val with = VideoTools.describe("a.mp4", VideoInfo(10.0, 640, 480, true), listOf(1.0, 6.0))
        assertTrue("0:10" in with && "640x480" in with && "0:01, 0:06" in with && "piste audio" in with)
        assertFalse("piste audio" in VideoTools.describe("a.mp4", VideoInfo(10.0, 640, 480, false), listOf(1.0)))
    }

    @Test fun unavailableTextKeepsThePath() {
        val t = VideoTools.unavailable("dir/a.mp4", "ffmpeg est introuvable")
        assertTrue("dir/a.mp4" in t && "ffmpeg" in t && "Bash" in t)
    }

    private fun makeVideo(): String? {
        val ffmpeg = VideoTools.find("ffmpeg") ?: return null
        val f = tmp.resolve("t.mp4").toString()
        val p = ProcessBuilder(ffmpeg, "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc=duration=7:size=1280x720:rate=10",
            "-f", "lavfi", "-i", "sine=frequency=440:duration=7", "-pix_fmt", "yuv420p", "-shortest", f).start()
        return if (p.waitFor() == 0) f else null
    }

    @Test fun probesAndExtractsRealFrames() {
        val video = makeVideo(); assumeTrue(video != null, "ffmpeg absent")
        val info = VideoTools.probe(video!!, VideoTools.find("ffprobe")!!)!!
        assertEquals(1280, info.width); assertEquals(720, info.height); assertTrue(info.hasAudio)
        assertEquals(7.0, info.durationSec, 0.5)
        val (text, frames) = VideoTools.attach(video, "t.mp4")
        assertEquals(3, frames.size, "7 s -> 3 images")
        assertTrue(frames.all { it[0] == 0xFF.toByte() && it[1] == 0xD8.toByte() && it.size > 1000 }, "JPEG valides")
        assertTrue("frames=\"3\"" in text && "piste audio" in text)
        val img = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(frames[0]))
        assertTrue(img.width <= VideoTools.MAX_WIDTH, "réduit à ${img.width}")
    }

    @Test fun unreadableFileGivesExplanationWithoutImages() {
        assumeTrue(VideoTools.find("ffmpeg") != null)
        val bad = tmp.resolve("bad.mp4").toFile().apply { writeText("pas une vidéo") }
        val (text, frames) = VideoTools.attach(bad.path, "bad.mp4")
        assertTrue(frames.isEmpty() && "illisible" in text)
    }
}
