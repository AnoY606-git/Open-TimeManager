package dev.opentimemanager.app.ui.components

import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Small, original line-icon set; no downloaded assets or runtime font dependency. */
enum class AppGlyph {
    Timer, History, Reports, Settings, Play, Pause, Stop, Plus, Search, ChevronRight,
    Edit, Delete, Download, Shield, Palette, Globe, Target, ArrowUpRight, Check,
    Folder, Lock, Note, Close, Archive, Notifications,
}

private val vectors: Map<AppGlyph, ImageVector> = AppGlyph.entries.associateWith { glyph ->
    ImageVector.Builder(glyph.name, 24.dp, 24.dp, 24f, 24f).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            when (glyph) {
                AppGlyph.Timer -> {
                    circle(12f, 13f, 8f)
                    moveTo(10f, 2f); lineTo(14f, 2f)
                    moveTo(12f, 5f); lineTo(12f, 2f)
                    moveTo(18f, 6f); lineTo(20f, 4f)
                    moveTo(12f, 9f); lineTo(12f, 13f); lineTo(15f, 15f)
                }
                AppGlyph.History -> {
                    moveTo(3f, 10f); curveTo(4f, 5.5f, 7.5f, 3f, 12f, 3f)
                    curveTo(17f, 3f, 21f, 7f, 21f, 12f)
                    curveTo(21f, 17f, 17f, 21f, 12f, 21f)
                    curveTo(8.5f, 21f, 5.5f, 19f, 4f, 16f)
                    moveTo(3f, 5f); lineTo(3f, 10f); lineTo(8f, 10f)
                    moveTo(12f, 7f); lineTo(12f, 12f); lineTo(15f, 14f)
                }
                AppGlyph.Reports -> {
                    moveTo(4f, 3f); lineTo(4f, 20f); lineTo(21f, 20f)
                    moveTo(8f, 15f); lineTo(8f, 11f)
                    moveTo(13f, 15f); lineTo(13f, 6f)
                    moveTo(18f, 15f); lineTo(18f, 9f)
                }
                AppGlyph.Settings -> {
                    moveTo(4f, 6f); lineTo(7f, 6f); moveTo(12f, 6f); lineTo(20f, 6f)
                    moveTo(4f, 12f); lineTo(13f, 12f); moveTo(18f, 12f); lineTo(20f, 12f)
                    moveTo(4f, 18f); lineTo(6f, 18f); moveTo(11f, 18f); lineTo(20f, 18f)
                    circle(9.5f, 6f, 2.5f); circle(15.5f, 12f, 2.5f); circle(8.5f, 18f, 2.5f)
                }
                AppGlyph.Play -> { moveTo(9f, 5f); lineTo(19f, 12f); lineTo(9f, 19f); close() }
                AppGlyph.Pause -> { moveTo(8f, 5f); lineTo(8f, 19f); moveTo(16f, 5f); lineTo(16f, 19f) }
                AppGlyph.Stop -> { moveTo(6f, 6f); lineTo(18f, 6f); lineTo(18f, 18f); lineTo(6f, 18f); close() }
                AppGlyph.Plus -> { moveTo(12f, 5f); lineTo(12f, 19f); moveTo(5f, 12f); lineTo(19f, 12f) }
                AppGlyph.Search -> { circle(10.5f, 10.5f, 6.5f); moveTo(16f, 16f); lineTo(21f, 21f) }
                AppGlyph.ChevronRight -> { moveTo(9f, 5f); lineTo(16f, 12f); lineTo(9f, 19f) }
                AppGlyph.Edit -> {
                    moveTo(14f, 5f); lineTo(19f, 10f); moveTo(4f, 20f); lineTo(5f, 14f)
                    lineTo(16f, 3f); curveTo(17f, 2f, 18f, 2f, 19f, 3f); lineTo(21f, 5f)
                    curveTo(22f, 6f, 22f, 7f, 21f, 8f); lineTo(10f, 19f); close()
                }
                AppGlyph.Delete -> {
                    moveTo(3f, 6f); lineTo(21f, 6f); moveTo(9f, 6f); lineTo(9f, 3f); lineTo(15f, 3f); lineTo(15f, 6f)
                    moveTo(5f, 6f); lineTo(6f, 21f); lineTo(18f, 21f); lineTo(19f, 6f)
                    moveTo(10f, 10f); lineTo(10f, 17f); moveTo(14f, 10f); lineTo(14f, 17f)
                }
                AppGlyph.Download -> {
                    moveTo(12f, 3f); lineTo(12f, 15f); moveTo(7f, 10f); lineTo(12f, 15f); lineTo(17f, 10f)
                    moveTo(4f, 16f); lineTo(4f, 21f); lineTo(20f, 21f); lineTo(20f, 16f)
                }
                AppGlyph.Shield -> {
                    moveTo(12f, 2f); lineTo(20f, 5f); lineTo(20f, 11f)
                    curveTo(20f, 16f, 17f, 20f, 12f, 22f); curveTo(7f, 20f, 4f, 16f, 4f, 11f)
                    lineTo(4f, 5f); close(); moveTo(8f, 12f); lineTo(11f, 15f); lineTo(16f, 9f)
                }
                AppGlyph.Palette -> {
                    moveTo(21f, 12f); curveTo(21f, 7f, 17f, 3f, 12f, 3f)
                    curveTo(7f, 3f, 3f, 7f, 3f, 12f); curveTo(3f, 17f, 7f, 21f, 12f, 21f)
                    curveTo(14f, 21f, 15f, 19f, 14f, 17f); curveTo(13f, 15f, 15f, 14f, 17f, 15f)
                    curveTo(19f, 16f, 21f, 15f, 21f, 12f); close()
                    circle(7.5f, 10f, 0.7f); circle(11f, 7f, 0.7f); circle(16f, 8f, 0.7f)
                }
                AppGlyph.Globe -> {
                    circle(12f, 12f, 9f); moveTo(3f, 12f); lineTo(21f, 12f)
                    moveTo(12f, 3f); curveTo(7f, 8f, 7f, 16f, 12f, 21f)
                    curveTo(17f, 16f, 17f, 8f, 12f, 3f)
                }
                AppGlyph.Target -> { circle(12f, 12f, 9f); circle(12f, 12f, 5f); circle(12f, 12f, 1f) }
                AppGlyph.ArrowUpRight -> { moveTo(6f, 18f); lineTo(18f, 6f); moveTo(6f, 6f); lineTo(18f, 6f); lineTo(18f, 18f) }
                AppGlyph.Check -> { moveTo(5f, 12f); lineTo(10f, 17f); lineTo(20f, 7f) }
                AppGlyph.Folder -> {
                    moveTo(3f, 5f); lineTo(10f, 5f); lineTo(12f, 8f); lineTo(21f, 8f)
                    lineTo(21f, 19f); lineTo(3f, 19f); close()
                }
                AppGlyph.Lock -> {
                    moveTo(7f, 10f); lineTo(7f, 7f); curveTo(7f, 0.5f, 17f, 0.5f, 17f, 7f); lineTo(17f, 10f)
                    moveTo(5f, 10f); lineTo(19f, 10f); lineTo(19f, 21f); lineTo(5f, 21f); close()
                    moveTo(12f, 14f); lineTo(12f, 17f)
                }
                AppGlyph.Note -> {
                    moveTo(5f, 3f); lineTo(19f, 3f); lineTo(19f, 16f); lineTo(14f, 21f); lineTo(5f, 21f); close()
                    moveTo(14f, 21f); lineTo(14f, 16f); lineTo(19f, 16f)
                    moveTo(9f, 8f); lineTo(15f, 8f); moveTo(9f, 12f); lineTo(15f, 12f)
                }
                AppGlyph.Close -> { moveTo(6f, 6f); lineTo(18f, 18f); moveTo(18f, 6f); lineTo(6f, 18f) }
                AppGlyph.Archive -> {
                    moveTo(3f, 3f); lineTo(21f, 3f); lineTo(21f, 7f); lineTo(3f, 7f); close()
                    moveTo(5f, 7f); lineTo(5f, 21f); lineTo(19f, 21f); lineTo(19f, 7f)
                    moveTo(9f, 12f); lineTo(15f, 12f)
                }
                AppGlyph.Notifications -> {
                    moveTo(5f, 17f); lineTo(7f, 14f); lineTo(7f, 9f)
                    curveTo(7f, 2f, 17f, 2f, 17f, 9f)
                    lineTo(17f, 14f); lineTo(19f, 17f); close()
                    moveTo(10f, 21f); lineTo(14f, 21f)
                }
            }
        }
    }.build()
}

private fun PathBuilder.circle(cx: Float, cy: Float, radius: Float) {
    val c = radius * 0.5522848f
    moveTo(cx + radius, cy)
    curveTo(cx + radius, cy + c, cx + c, cy + radius, cx, cy + radius)
    curveTo(cx - c, cy + radius, cx - radius, cy + c, cx - radius, cy)
    curveTo(cx - radius, cy - c, cx - c, cy - radius, cx, cy - radius)
    curveTo(cx + c, cy - radius, cx + radius, cy - c, cx + radius, cy)
    close()
}

@Composable
fun AppIcon(
    glyph: AppGlyph,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    Icon(requireNotNull(vectors[glyph]), contentDescription, modifier, tint)
}
