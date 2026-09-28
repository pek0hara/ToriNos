package com.nostr.torinos.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.nostr.torinos.model.CustomReaction

/** カスタム絵文字の表示と所属セットへの遷移を共通化する。 */
@Composable
internal fun CustomReactionLink(
    reaction: CustomReaction,
    containerSize: Dp,
    imageSize: Dp = containerSize,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val decodeSizePx = with(density) { imageSize.roundToPx() }
    val openCustomEmoji = LocalCustomEmojiNavigator.current
    Box(
        modifier = modifier
            .size(containerSize)
            .clickable {
                openCustomEmoji(CustomEmojiOpenRequest.of(reaction.shortcode, reaction.imageUrl))
            },
        contentAlignment = Alignment.Center,
    ) {
        NetworkImage(
            url = reaction.imageUrl,
            contentDescription = ":${reaction.shortcode}:",
            contentScale = ContentScale.Fit,
            maxDecodeSizePx = decodeSizePx,
            modifier = Modifier.size(imageSize),
        )
    }
}
