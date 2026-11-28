package org.lexora.study

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

// Preserved primary blue identity + upgraded vibrant accessible green (#22C55E / #4ADE80)
val blue = Color(0xffa6caff)
val blueDeep = Color(0xff0f2744)
val blueBorder = Color(0xff385270)
val blueSurface = Color(0xff1b2d45)

val green = Color(0xff22c55e)
val greenText = Color(0xff4ade80)
val greenBg = Color(0xff103320)
val greenBorder = Color(0xff1f7a44)
val mint = green // Backwards-compatible alias upgraded to vibrant accessible green

val ink = Color(0xff0b1320)
val panel = Color(0xff152232)
val panelSubtle = Color(0xff111c2b)
val panelBorder = Color(0xff2a3d52)
val faint = Color(0xffb8c7d8)
val amber = Color(0xfff59e0b)
val amberBg = Color(0xff33260d)
val coral = Color(0xfff87171)
val coralBg = Color(0xff3a181c)
val textMain = Color(0xfff1f5f9)

val LexoraColorScheme = darkColorScheme(
    primary = blue,
    onPrimary = Color(0xff0b223d),
    primaryContainer = blueSurface,
    onPrimaryContainer = blue,
    secondary = green,
    onSecondary = Color(0xff052e16),
    secondaryContainer = greenBg,
    onSecondaryContainer = greenText,
    background = ink,
    onBackground = textMain,
    surface = ink,
    onSurface = textMain,
    surfaceContainer = panel,
    surfaceContainerHigh = Color(0xff1b2b3e),
    onSurfaceVariant = faint,
    outline = Color(0xff465c73),
    outlineVariant = panelBorder,
    error = coral,
    onError = Color(0xff2c0b0e),
)

@Composable
fun Heading(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.fillMaxWidth(),
        fontSize = 26.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.5).sp,
        color = textMain,
        softWrap = true,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.fillMaxWidth(),
        fontSize = 18.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold,
        color = textMain,
        softWrap = true,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun Label(text: String, color: Color = blue, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(Locale.ENGLISH),
        modifier = modifier,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        fontWeight = FontWeight.Bold,
        color = color,
        letterSpacing = 1.2.sp,
        softWrap = true,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun Hint(text: String, modifier: Modifier = Modifier, color: Color = faint) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        style = MaterialTheme.typography.bodyMedium,
        lineHeight = 20.sp,
        softWrap = true,
    )
}

@Composable
fun Sheet(
    modifier: Modifier = Modifier,
    accentBorder: Color = panelBorder,
    background: Color = panel,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .border(1.dp, accentBorder, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
fun Primary(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) = Primary(text = text, enabled = enabled, modifier = Modifier, onClick = onClick)

@Composable
fun Primary(
    text: String,
    enabled: Boolean = true,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        enabled = enabled,
        shape = RoundedCornerShape(13.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = blue,
            contentColor = Color(0xff0b223d),
            disabledContainerColor = Color(0xff233345),
            disabledContentColor = Color(0xff778a9e),
        ),
    ) {
        Text(
            text = text,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            softWrap = true,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun SuccessPrimary(
    text: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        enabled = enabled,
        shape = RoundedCornerShape(13.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = green,
            contentColor = Color(0xff052e16),
            disabledContainerColor = Color(0xff1e3328),
            disabledContentColor = Color(0xff729380),
        ),
    ) {
        Text(
            text = text,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            softWrap = true,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun Secondary(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) = Secondary(text = text, enabled = enabled, modifier = Modifier, onClick = onClick)

@Composable
fun Secondary(
    text: String,
    enabled: Boolean = true,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        enabled = enabled,
        shape = RoundedCornerShape(13.dp),
        border = BorderStroke(1.dp, if (enabled) blueBorder else Color(0xff263646)),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Color(0xff132030),
            contentColor = blue,
            disabledContentColor = Color(0xff6b7d90),
        ),
    ) {
        Text(
            text = text,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            softWrap = true,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun Progress(value: Float, color: Color = green, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = { value.coerceIn(0f, 1f) },
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(4.dp)),
        color = color,
        trackColor = Color(0xff233344),
    )
}

@Composable
fun Metric(
    value: String,
    title: String,
    modifier: Modifier = Modifier,
    accent: Color = textMain,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(panel)
            .border(1.dp, panelBorder, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = value,
            fontSize = 19.sp,
            lineHeight = 23.sp,
            fontWeight = FontWeight.SemiBold,
            color = accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = title,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            color = faint,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun BadgePill(
    text: String,
    color: Color = blue,
    background: Color = blueSurface,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Bold,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
fun StatusPill(status: String, modifier: Modifier = Modifier) {
    val (fg, bg) = when (status) {
        "Mastered", "Low", "Target Range Indicated", "Positive", "Completed", "Goal met" -> greenText to greenBg
        "Moderate", "Approaching Target", "Needs Review", "Needs Reassessment", "Medium", "Practice Needed" -> amber to amberBg
        "High", "Below Target" -> coral to coralBg
        else -> blue to blueSurface
    }
    BadgePill(status, color = fg, background = bg, modifier = modifier)
}

@Composable
fun Page(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
fun Pick(
    label: String,
    value: String,
    options: List<String>,
    onChange: (String) -> Unit,
) = Pick(label = label, value = value, options = options, modifier = Modifier, onChange = onChange)

@Composable
fun Pick(
    label: String,
    value: String,
    options: List<String>,
    modifier: Modifier,
    onChange: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { open = true },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, panelBorder),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = panelSubtle,
                contentColor = textMain,
            ),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "$label · $value",
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Start,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Choose $label",
                    tint = blue,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier
                .widthIn(min = 200.dp, max = 340.dp)
                .heightIn(max = 360.dp),
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = option,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = if (option == value) blue else textMain,
                            fontWeight = if (option == value) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    onClick = {
                        open = false
                        onChange(option)
                    },
                )
            }
        }
    }
}

@Composable
fun ExpandableInfo(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
) {
    var open by remember { mutableStateOf(initiallyExpanded) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(panelSubtle)
            .border(1.dp, panelBorder, RoundedCornerShape(12.dp))
            .clickable { open = !open }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Default.Info, contentDescription = null, tint = blue, modifier = Modifier.size(16.dp))
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = blue,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (open) "Collapse" else "Expand",
                tint = faint,
                modifier = Modifier.size(18.dp),
            )
        }
        AnimatedVisibility(visible = open) {
            Hint(body)
        }
    }
}

@Composable
fun EmptyStateCard(
    title: String,
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Sheet(background = panelSubtle) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = textMain)
        Hint(message)
        if (actionLabel != null && onAction != null) {
            Secondary(actionLabel, onClick = onAction)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipStrip(
    options: List<String>,
    selected: String,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(option) },
                label = {
                    Text(
                        text = option,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 13.sp,
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = blueSurface,
                    selectedLabelColor = blue,
                    containerColor = panelSubtle,
                    labelColor = faint,
                ),
            )
        }
    }
}
