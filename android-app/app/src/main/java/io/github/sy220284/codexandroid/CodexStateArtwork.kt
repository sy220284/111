package io.github.sy220284.codexandroid

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** 装饰性插画只承载状态形状；说明、操作和读屏文本仍由真实页面提供。 */
@Composable
fun CodexStateArtwork(@DrawableRes artworkRes: Int, modifier: Modifier = Modifier) {
    val dark by UiPreferences.darkMode.collectAsStateWithLifecycle()
    val resource = if (dark) when (artworkRes) {
        R.drawable.ic_empty_thread -> R.drawable.ic_empty_thread_dark
        R.drawable.ic_empty_content -> R.drawable.ic_empty_content_dark
        R.drawable.ic_empty_no_results -> R.drawable.ic_empty_no_results_dark
        R.drawable.ic_empty_request_failed -> R.drawable.ic_empty_request_failed_dark
        R.drawable.ic_empty_diff -> R.drawable.ic_empty_diff_dark
        R.drawable.ic_empty_approval -> R.drawable.ic_empty_approval_dark
        R.drawable.ic_empty_connection -> R.drawable.ic_empty_connection_dark
        else -> artworkRes
    } else artworkRes
    Image(painter = painterResource(resource), contentDescription = null, modifier = modifier)
}
