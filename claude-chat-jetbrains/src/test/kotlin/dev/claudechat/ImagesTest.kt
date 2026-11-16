package dev.claudechat

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO

class ImagesTest {
    private fun png(w: Int, h: Int) = Base64.getEncoder().encodeToString(
        ByteArrayOutputStream().also { ImageIO.write(BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB), "png", it) }.toByteArray())

    @Test fun decodesValidImageAndRejectsGarbage() {
        assertEquals(30, Images.decode(png(30, 20))!!.width)
        assertNull(Images.decode("pas une image"))
        assertNull(Images.decode(Base64.getEncoder().encodeToString("xx".toByteArray())))
    }

    @Test fun thumbIsSavedOnceAndReusedWithItsSize() {
        val dir = java.nio.file.Files.createTempDirectory("thumbs")
        DataHome.override = dir
        try {
            val t = Images.thumb(png(1100, 400))!!
            assertEquals(110, t.w); assertEquals(40, t.h)
            assertTrue(java.nio.file.Files.isRegularFile(java.nio.file.Path.of(t.path)))
            val again = Images.thumb(png(1100, 400))!!
            assertEquals(t.path, again.path); assertEquals(110, again.w); assertEquals(40, again.h)
            assertNull(Images.thumb("pas une image"))
        } finally { DataHome.override = null; dir.toFile().deleteRecursively() }
    }

    @Test fun scalesDownKeepingRatioAndNeverUp() {
        val big = Images.decode(png(1000, 500))!!
        val i = Images.scaledIcon(big, 100, 100)
        assertEquals(100, i.iconWidth); assertEquals(50, i.iconHeight)
        val small = Images.scaledIcon(Images.decode(png(40, 20))!!, 100, 100)
        assertEquals(40, small.iconWidth); assertEquals(20, small.iconHeight)
    }
}
