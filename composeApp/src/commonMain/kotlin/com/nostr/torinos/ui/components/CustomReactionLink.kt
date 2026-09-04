package com.nostr.torinos.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import com.nostr.torinos.model.CustomReaction
import com.nostr.torinos.network.CustomEmojiStore

/** カスタム絵文字の表示と所属セットへの遷移を共通化する。 */
@Composable
internal fun CustomReactionLink(
    reaction: CustomReaction,
    containerSize: Dp,
    imageSize: Dp = containerSize,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(containerSize)
            .clickable {
                CustomEmojiStore.requestOpenSearch(
                    shortcode = reaction.shortcode,
                    imageUrl = reaction.imageUrl,
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        NetworkImage(
            url = reaction.imageUrl,
            contentDescription = ":${reaction.shortcode}:",
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(imageSize),
        )
    }
}
