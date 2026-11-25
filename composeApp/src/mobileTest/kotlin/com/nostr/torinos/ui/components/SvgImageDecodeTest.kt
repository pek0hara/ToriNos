package com.nostr.torinos.ui.components

import androidx.compose.ui.layout.ContentScale
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * バッジ画像やユーザーアイコンの SVG が表示できること。coil-svg の SvgDecoder は ServiceLoader で
 * 自動登録されるため、アプリの ImageLoader 構成で明示的に追加していなくても読めることを確かめる。
 */
class SvgImageDecodeTest {
    // nokakoi family user バッジ(https://nokakoi.com/media/nokakoi-c.svg)と同じく、
    // width/height を持たず viewBox だけを持つ SVG。
    private val viewBoxOnlySvg = """<svg viewBox="0 0 72 72" xmlns="http://www.w3.org/2000/svg" version="1.1">""" +
        """<circle cx="36" cy="36" r="30" fill="#e83a99"></circle></svg>"""

    // width/height を持つ大きめの SVG(アイコンに使われているものに多い形)。
    private val sizedSvg = """<?xml version="1.0" encoding="UTF-8"?>""" +
        """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024" width="1024" height="1024">""" +
        """<rect width="1024" height="1024" fill="#123456"/></svg>"""

    private val context = PlatformContext.INSTANCE
    private val loader = ImageLoader.Builder(context).build()

    private fun dataUri(svg: String) = "data:image/svg+xml;base64," + Base64.encode(svg.encodeToByteArray())

    @Test
    fun decodesBadgeSvgWithOnlyViewBox() = runTest {
        assertDecodes(
            ImageRequest.Builder(context).data(viewBoxOnlySvg.encodeToByteArray()).size(72, 72).build(),
        )
    }

    /** [AvatarImage] と同じく、表示サイズでデコードし切り抜く設定で読めること。 */
    @Test
    fun decodesAvatarSvg() = runTest {
        for (svg in listOf(viewBoxOnlySvg, sizedSvg)) {
            assertDecodes(
                buildNetworkImageRequest(
                    context = context,
                    url = dataUri(svg),
                    contentScale = ContentScale.Crop,
                    maxDecodeSizePx = 120,
                ),
            )
        }
    }

    /** タイムラインのアイコンなど、先頭フレームだけにする(animate=false)設定でも読めること。 */
    @Test
    fun decodesSvgWithoutAnimation() = runTest {
        assertDecodes(
            buildNetworkImageRequest(
                context = context,
                url = dataUri(sizedSvg),
                contentScale = ContentScale.Crop,
                maxDecodeSizePx = 120,
                animate = false,
            ),
        )
    }

    private suspend fun assertDecodes(request: ImageRequest) {
        when (val result = loader.execute(request)) {
            is SuccessResult -> {
                assertTrue(result.image.width > 0)
                assertTrue(result.image.height > 0)
            }
            is ErrorResult -> fail("SVG decode failed: ${result.throwable}")
        }
    }
}
