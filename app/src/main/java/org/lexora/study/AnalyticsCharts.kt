package org.lexora.study

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class ChartBarPoint(
    val label: String,
    val fullDate: String,
    val value: Float,
    val formattedValue: String,
)

@Composable
fun InteractiveBarChart(
    title: String,
    unit: String,
    points: List<ChartBarPoint>,
    accent: Color = blue,
    emptyMessage: String = "No recorded data in this period yet.",
) {
    val visible = remember(points) {
        if (points.size <= 14) points else points.takeLast(14)
    }
    var selectedIdx by remember(visible) { mutableIntStateOf(visible.lastIndex.coerceAtLeast(0)) }

    Sheet {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = textMain,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            BadgePill(unit, color = accent, background = panelSubtle)
        }

        if (visible.isEmpty() || visible.all { it.value <= 0f }) {
            Hint(emptyMessage)
            return@Sheet
        }

        val maxVal = visible.maxOf { it.value }.coerceAtLeast(1f)
        val sel = visible.getOrNull(selectedIdx)
        if (sel != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(panelSubtle)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(sel.fullDate, fontSize = 12.sp, color = faint)
                Text(sel.formattedValue, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = accent)
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .height(120.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            visible.forEachIndexed { idx, p ->
                val frac = (p.value / maxVal).coerceIn(0f, 1f)
                val isSel = idx == selectedIdx
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { selectedIdx = idx },
                    verticalArrangement = Arrangement.Bottom,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.weight((1f - frac).coerceAtLeast(0.05f)))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .weight(frac.coerceAtLeast(0.05f))
                            .clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp))
                            .background(if (isSel) accent else accent.copy(alpha = 0.50f)),
                    )
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(visible.first().label, fontSize = 11.sp, color = faint)
            Text("Peak: ${visible.maxBy { it.value }.formattedValue}", fontSize = 11.sp, color = faint)
            Text(visible.last().label, fontSize = 11.sp, color = faint)
        }
    }
}

@Composable
fun InteractiveTrendLineChart(
    title: String,
    unit: String,
    points: List<ChartBarPoint>,
    lineColor: Color = green,
    emptyMessage: String = "Need at least 2 recorded points to plot a trend.",
) {
    val visible = remember(points) { if (points.size <= 20) points else points.takeLast(20) }
    var selectedIdx by remember(visible) { mutableIntStateOf(visible.lastIndex.coerceAtLeast(0)) }

    Sheet {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = textMain,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            BadgePill(unit, color = lineColor, background = panelSubtle)
        }

        if (visible.size < 2) {
            Hint(emptyMessage)
            return@Sheet
        }

        val maxVal = visible.maxOf { it.value }.coerceAtLeast(1f)
        val minVal = visible.minOf { it.value }.coerceAtMost(0f)
        val span = (maxVal - minVal).coerceAtLeast(1f)

        val sel = visible.getOrNull(selectedIdx)
        if (sel != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(panelSubtle)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(sel.fullDate, fontSize = 12.sp, color = faint)
                Text(sel.formattedValue, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = lineColor)
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(panelSubtle)
                .padding(horizontal = 12.dp, vertical = 14.dp),
        ) {
            val stepX = size.width / (visible.size - 1).coerceAtLeast(1)
            val path = Path()
            visible.forEachIndexed { idx, pt ->
                val x = idx * stepX
                val y = size.height - ((pt.value - minVal) / span) * size.height
                if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color = lineColor, style = Stroke(width = 5f, cap = StrokeCap.Round))
            visible.forEachIndexed { idx, pt ->
                val x = idx * stepX
                val y = size.height - ((pt.value - minVal) / span) * size.height
                drawCircle(
                    color = if (idx == selectedIdx) textMain else lineColor,
                    radius = if (idx == selectedIdx) 8f else 5f,
                    center = Offset(x, y),
                )
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(visible.first().label, fontSize = 11.sp, color = faint)
            Text("Latest: ${visible.last().formattedValue}", fontSize = 11.sp, color = lineColor, fontWeight = FontWeight.SemiBold)
            Text(visible.last().label, fontSize = 11.sp, color = faint)
        }
    }
}

@Composable
fun BreakdownBarRow(
    label: String,
    valueText: String,
    fraction: Float,
    barColor: Color = green,
    subtext: String? = null,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                modifier = Modifier.weight(1f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = textMain,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                valueText,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = barColor,
                maxLines = 1,
            )
        }
        Progress(fraction, color = barColor)
        if (!subtext.isNullOrBlank()) {
            Text(subtext, fontSize = 11.sp, color = faint, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
