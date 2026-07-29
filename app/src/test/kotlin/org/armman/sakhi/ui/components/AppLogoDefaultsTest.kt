package org.armman.sakhi.ui.components

import androidx.compose.ui.unit.dp
import org.armman.sakhi.ui.theme.Dimens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the logo lockup against the clipping regression fixed in the "tagline truncated in header"
 * bug: [AppLogo] sizes itself from [AppLogoDefaults.ASPECT_RATIO], so if the PNG is ever swapped for
 * one with different intrinsic dimensions the ratio silently stops matching and the wordmark gets
 * letterboxed or cropped again. These tests read the real asset's PNG header and fail loudly instead.
 */
class AppLogoDefaultsTest {

  @Test
  fun `aspect ratio matches the logo asset's intrinsic dimensions`() {
    val (width, height) = readPngDimensions(logoAsset())

    assertEquals(AppLogoDefaults.INTRINSIC_WIDTH_PX.toInt(), width)
    assertEquals(AppLogoDefaults.INTRINSIC_HEIGHT_PX.toInt(), height)
    assertEquals(width.toFloat() / height.toFloat(), AppLogoDefaults.ASPECT_RATIO, 0f)
  }

  @Test
  fun `logo asset is portrait, so a square box would letterbox it`() {
    assertTrue(
      "Logo is expected to be taller than it is wide; revisit AppLogo sizing if this changes.",
      AppLogoDefaults.ASPECT_RATIO < 1f,
    )
  }

  @Test
  fun `logo content padding leaves the artwork the majority of the header logo box`() {
    assertTrue(
      "Logo needs a positive inset; the asset bleeds to its edges and would otherwise sit flush.",
      Dimens.LogoContentPadding > 0.dp,
    )
    assertTrue(
      "Inset must not dominate the box, or the artwork becomes illegible.",
      Dimens.LogoContentPadding * 2 < Dimens.HeaderLogoHeight / 2,
    )
  }

  private fun logoAsset(): File {
    // Unit tests run with the module directory (app/) as the working directory.
    val asset = File("src/main/res/drawable-nodpi/logo_arogya_sakhi.png")
    assertTrue("Logo asset not found at ${asset.absolutePath}", asset.exists())
    return asset
  }

  /** Reads width/height from a PNG's IHDR chunk without pulling in an image-decoding dependency. */
  private fun readPngDimensions(file: File): Pair<Int, Int> {
    val header = file.inputStream().use { input ->
      ByteArray(PNG_HEADER_BYTES).also { buffer ->
        val read = input.read(buffer)
        assertEquals("Truncated PNG header in ${file.name}", PNG_HEADER_BYTES, read)
      }
    }
    return readBigEndianInt(header, WIDTH_OFFSET) to readBigEndianInt(header, HEIGHT_OFFSET)
  }

  private fun readBigEndianInt(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF shl 24) or
      (bytes[offset + 1].toInt() and 0xFF shl 16) or
      (bytes[offset + 2].toInt() and 0xFF shl 8) or
      (bytes[offset + 3].toInt() and 0xFF)

  private companion object {
    /** 8-byte PNG signature + 8-byte IHDR chunk header, then width and height as 4-byte ints. */
    const val WIDTH_OFFSET = 16
    const val HEIGHT_OFFSET = 20
    const val PNG_HEADER_BYTES = 24
  }
}
