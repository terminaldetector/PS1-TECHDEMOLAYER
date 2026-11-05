package com.metro.opticalshell.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * The Metro Pivot pattern (TZ §3.6), for the shell's OWN screens only.
 *
 * TZ §3.6 is explicit that Pivot/Panorama cannot be injected into third-party
 * APKs: without modifying the app it is unreachable. So this is used for
 * settings, diagnostics and calibration, where we own the surface.
 *
 * The WP characteristics that make it read as Pivot rather than as tabs: the
 * headers scroll with the content, the neighbouring header titles stay
 * partially visible, and the active title is large and light-weight.
 */
@Composable
fun MetroPivot(
    titles: List<String>,
    modifier: Modifier = Modifier,
    initialPage: Int = 0,
    content: @Composable (page: Int) -> Unit,
) {
    val pagerState = rememberPagerState(
        initialPage = initialPage.coerceIn(0, (titles.size - 1).coerceAtLeast(0)),
        pageCount = { titles.size },
    )
    val scope = rememberCoroutineScope()
    val headerScroll = rememberScrollState()

    Column(modifier = modifier.fillMaxSize().background(MetroColors.Black)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(headerScroll)
                .padding(start = 20.dp, top = 10.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.Start,
        ) {
            titles.forEachIndexed { index, title ->
                val active = index == pagerState.currentPage
                Text(
                    text = title,
                    fontSize = if (active) 30.sp else 26.sp,
                    fontWeight = FontWeight.Light,
                    color = if (active) MetroColors.White else MetroColors.Dim,
                    modifier = Modifier
                        .clickable(onClickLabel = title) {
                            scope.launch { pagerState.animateScrollToPage(index) }
                        }
                        .padding(end = 24.dp),
                )
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            content(page)
        }
    }
}

/**
 * The Metro App Bar with an ellipsis (TZ §3.7).
 *
 * Collapsed it shows up to four icon commands; expanded, the ellipsis reveals
 * the labelled menu items. As with Pivot, this applies only to our own screens.
 */
@Composable
fun MetroAppBar(
    primary: List<AppBarCommand>,
    modifier: Modifier = Modifier,
    overflow: List<AppBarCommand> = emptyList(),
) {
    var expanded by remember { mutableStateOf(false) }
    val accent = LocalAccent.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MetroColors.Black),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            primary.take(4).forEach { command ->
                Box(
                    modifier = Modifier
                        .clickable(onClickLabel = command.label, onClick = command.onClick)
                        .padding(10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = command.glyph, fontSize = 18.sp, color = MetroColors.White)
                }
            }
            if (overflow.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .clickable(onClickLabel = "More") { expanded = !expanded }
                        .padding(10.dp),
                ) {
                    Text(text = "⋯", fontSize = 18.sp, color = accent)
                }
            }
        }

        if (expanded) {
            overflow.forEach { command ->
                Text(
                    text = command.label,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClickLabel = command.label) {
                            expanded = false
                            command.onClick()
                        }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
        }
    }
}

data class AppBarCommand(
    val glyph: String,
    val label: String,
    val onClick: () -> Unit,
)
