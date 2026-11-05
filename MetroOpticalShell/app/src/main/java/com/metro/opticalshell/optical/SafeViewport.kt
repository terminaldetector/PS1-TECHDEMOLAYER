package com.metro.opticalshell.optical

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.dp

/**
 * Applies the optical profile over any presentation (TZ section 6).
 *
 * Structure, and why it is this way round:
 *
 *   systemBarsPadding()   <- physical facts: cutout, status bar, nav bar
 *     optical insets      <- the user's lens hypothesis
 *       content           <- the presentation, unchanged
 *     peripheral dimming  <- drawn on top, never on the content's own layer
 *
 * The system insets are applied FIRST and separately, because TZ 6 requires
 * them to be honoured regardless of the optical profile: an optical inset of
 * zero must still keep content out of the cutout.
 *
 * When the profile is disabled this composable reduces to system insets alone,
 * so switching optics off restores the plain shell exactly (TZ 6, A13).
 */
@Composable
fun SafeViewport(
    profile: OpticalProfile,
    modifier: Modifier = Modifier,
    applySystemInsets: Boolean = true,
    content: @Composable () -> Unit,
) {
    val base = if (applySystemInsets) modifier.systemBarsPadding() else modifier

    if (!profile.enabled) {
        Box(modifier = base.fillMaxSize()) { content() }
        return
    }

    val p = profile.clamped()

    BoxWithConstraints(modifier = base.fillMaxSize()) {
        val w = maxWidth
        val h = maxHeight

        // Optical insets as fractions of the container, each edge independent.
        val left = w * p.insetLeftFraction
        val right = w * p.insetRightFraction
        val top = h * p.insetTopFraction
        val bottom = h * p.insetBottomFraction

        // Optical centre offset, expressed as extra padding on the far side so
        // the content box never leaves the screen.
        val dx = w * (p.opticalCenterX - 0.5f)
        val dy = h * (p.opticalCenterY - 0.5f)

        val padding = PaddingValues(
            start = (left + dx).coerceAtLeast(0.dp),
            end = (right - dx).coerceAtLeast(0.dp),
            top = (top + dy).coerceAtLeast(0.dp),
            bottom = (bottom - dy).coerceAtLeast(0.dp),
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .then(
                    if (p.peripheralDimming > 0f) {
                        Modifier.peripheralDim(p.peripheralDimming, p.opticalCenterX, p.opticalCenterY)
                    } else {
                        Modifier
                    },
                )
                .opticalMagnifier(p.magnifier),
        ) {
            content()
        }
    }
}

/**
 * Radial dimming toward the edges.
 *
 * Drawn over the content with drawWithContent so it darkens what is there
 * rather than tinting the tiles' own colours. It is a comfort aid only: TZ 6 is
 * clear that no amount of shading fixes a lens's physical quality.
 */
fun Modifier.peripheralDim(
    strength: Float,
    centerX: Float = 0.5f,
    centerY: Float = 0.5f,
): Modifier = this.drawWithContent {
    drawContent()
    val s = strength.coerceIn(0f, 1f)
    if (s <= 0f) return@drawWithContent
    val centre = Offset(size.width * centerX, size.height * centerY)
    val radius = maxOf(size.width, size.height) * 0.75f
    drawRect(
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0.0f to Color.Transparent,
                0.55f to Color.Transparent,
                1.0f to Color.Black.copy(alpha = s),
            ),
            center = centre,
            radius = radius,
        ),
        size = Size(size.width, size.height),
    )
}

/**
 * A single magnifying region (TZ 6: one region is in Operation 01 scope).
 *
 * Implementation note: the content is recorded into a GraphicsLayer ONCE and
 * then drawn twice — normally, and again scaled inside a circular clip. That
 * avoids composing the subtree twice, which a naive implementation would do.
 *
 * What this is NOT: it does not correct lens distortion and it does not
 * compensate chromatic aberration. TZ 6 lists those as extension points and
 * forbids faking them, so they are absent rather than approximated. Magnifying
 * also cannot add detail the panel never rendered.
 */
fun Modifier.opticalMagnifier(region: MagnifierRegion?): Modifier = composed {
    if (region == null) return@composed Modifier

    val r = region.clamped()
    val layer = rememberGraphicsLayer()

    Modifier.drawWithContent {
        // Record once.
        layer.record { this@drawWithContent.drawContent() }

        // Base pass.
        drawLayer(layer)

        // Magnified pass, clipped to the lens circle.
        val centre = Offset(size.width * r.centerX, size.height * r.centerY)
        val radiusPx = minOf(size.width, size.height) * r.radiusFraction

        val path = Path().apply {
            addOval(
                Rect(
                    left = centre.x - radiusPx,
                    top = centre.y - radiusPx,
                    right = centre.x + radiusPx,
                    bottom = centre.y + radiusPx,
                ),
            )
        }

        clipPath(path) {
            scale(scale = r.magnification, pivot = centre) {
                drawLayer(layer)
            }
        }

        // A thin ring so the user can see where the lens region is.
        drawCircle(
            color = Color.White.copy(alpha = 0.35f),
            radius = radiusPx,
            center = centre,
            style = Stroke(width = 1.5f),
        )
    }
}
