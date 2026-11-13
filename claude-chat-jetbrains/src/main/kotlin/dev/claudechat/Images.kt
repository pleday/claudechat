package dev.claudechat

import com.intellij.openapi.diagnostic.Logger
import java.util.Base64
import javax.swing.Icon

/** Utilitaires d'images pour les pièces jointes : lecture du presse-papiers, décodage, miniatures. */
object Images {
    private val LOG = Logger.getInstance(Images::class.java)

    /** Image du presse-papiers encodée en PNG : via `imageFlavor`, sinon via un format image (png, jpeg…) lu en flux (Wayland). */
    fun clipboardPng(tr: java.awt.datatransfer.Transferable): ByteArray? {
        fun encode(img: java.awt.Image): ByteArray? {
            val w = img.getWidth(null); val h = img.getHeight(null)
            if (w <= 0 || h <= 0) return null
            val bi = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            bi.createGraphics().apply { drawImage(img, 0, 0, null); dispose() }
            return java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(bi, "png", it) }.toByteArray()
        }
        if (tr.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.imageFlavor)) {
            (runCatching { tr.getTransferData(java.awt.datatransfer.DataFlavor.imageFlavor) as? java.awt.Image }
                .onFailure { LOG.info("Collage : imageFlavor illisible : $it") }.getOrNull())?.let { encode(it)?.let { b -> return b } }
        }
        val streams = tr.transferDataFlavors.filter { it.primaryType == "image" }
            .sortedBy { if (it.subType == "png") 0 else 1 }
        for (f in streams) {
            val data = runCatching { tr.getTransferData(f) }.onFailure { LOG.info("Collage : ${f.mimeType} illisible : $it") }.getOrNull()
            val bytes = (data as? java.io.InputStream)?.use { it.readBytes() }
            val img = when {
                bytes != null -> runCatching { javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(bytes)) }.getOrNull()
                data is java.awt.Image -> data
                else -> null
            }
            if (img != null) encode(img)?.let { return it }
        }
        return null
    }

    fun decode(base64: String): java.awt.image.BufferedImage? =
        runCatching { javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(Base64.getDecoder().decode(base64))) }.getOrNull()

    fun scaledIcon(bi: java.awt.image.BufferedImage, maxW: Int, maxH: Int): Icon {
        val k = minOf(maxW.toDouble() / bi.width, maxH.toDouble() / bi.height, 1.0)
        val w = (bi.width * k).toInt().coerceAtLeast(1); val h = (bi.height * k).toInt().coerceAtLeast(1)
        if (w == bi.width && h == bi.height) return javax.swing.ImageIcon(bi)
        // réduction en deux temps de plus d'un facteur 2 : l'interpolation bilinéaire seule crénelle
        var cur = bi
        var cw = bi.width; var ch = bi.height
        while (cw / 2 >= w && ch / 2 >= h) { cw /= 2; ch /= 2; cur = draw(cur, cw, ch) }
        return javax.swing.ImageIcon(draw(cur, w, h))
    }

    private fun draw(src: java.awt.image.BufferedImage, w: Int, h: Int): java.awt.image.BufferedImage {
        val out = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        out.createGraphics().apply {
            setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(src, 0, 0, w, h, null); dispose()
        }
        return out
    }
}
