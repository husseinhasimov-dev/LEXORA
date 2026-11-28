package org.lexora.study

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private val monthFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)
private val fullDayFormatter = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.ENGLISH)
private val shortDayFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)
private val weekdayFormatter = DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)
private val weekdayHeaders = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

@Composable
fun TotalStatisticsOverview(
    stats: Stats,
    goals: GoalCounts,
    summary: HistorySummary,
) {
    val bestStreak = maxOf(stats.streak, summary.bestStreak)
    val avgText = String.format(Locale.ENGLISH, "%.1f", summary.averagePerActiveDay)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Metric(goals.learned.toString(), "Words learned", Modifier.weight(1f))
            Metric(stats.started.toString(), "Words studied", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Metric(summary.totalReviews.toString(), "Total reviews", Modifier.weight(1f))
            Metric(summary.activeDays.toString(), "Active days", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Metric("${stats.streak}d · best ${bestStreak}d", "Streak", Modifier.weight(1f))
            Metric(formatStudyTime(stats.minutes), "Total study time", Modifier.weight(1f))
        }
        Sheet {
            Label("Overall totals")
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${goals.covered} / 6,000",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = textMain,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Hint("Covered")
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "$avgText / day",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = greenText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Hint("Daily avg")
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Text(
                        "${summary.goalsMetDays} days",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = blue,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Hint("Goals met")
                }
            }
            summary.bestDay?.let { best ->
                val formatted = runCatching { LocalDate.parse(best.day).format(shortDayFormatter) }
                    .getOrDefault(best.day)
                Hint("Best day · ${best.learned} words learned on $formatted (${best.reviews} reviews)")
            } ?: Hint("Complete your first study session to see daily records and streaks.")
        }
    }
}

@Composable
fun StudyCalendarAndTable(
    records: List<DailyRecord>,
    selectedDay: String,
    selectedDayWords: List<Word>,
    dailyGoal: Int,
    onSelectDay: (String) -> Unit,
    onOpenWord: (String) -> Unit,
) {
    val today = remember { LocalDate.now() }
    val currentMonth = remember(today) { YearMonth.from(today) }
    var viewMode by rememberSaveable { mutableStateOf("Calendar") }
    var tableScope by rememberSaveable { mutableStateOf("Month") }
    var monthText by rememberSaveable { mutableStateOf(currentMonth.toString()) }
    val activeMonth = remember(monthText) {
        runCatching { YearMonth.parse(monthText) }.getOrDefault(currentMonth)
    }

    val recordsByDay = remember(records) { records.associateBy { it.day } }
    val monthSummary = remember(activeMonth, records) { summarizeMonth(activeMonth, records) }
    val grid = remember(activeMonth, recordsByDay) { buildMonthGrid(activeMonth, recordsByDay) }
    val effectiveDay = selectedDay.ifEmpty { today.toString() }
    val selectedRecord = recordsByDay[effectiveDay]

    Sheet {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Label("Daily learning log")
                Text(
                    "Calendar & Table",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ModeToggle(
                options = listOf("Calendar", "Table"),
                selected = viewMode,
                onSelect = { viewMode = it },
            )
        }

        // Month selector bar (used in Calendar mode and Monthly Table scope)
        if (viewMode == "Calendar" || tableScope == "Month") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xff121d2b), RoundedCornerShape(12.dp))
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(onClick = { monthText = activeMonth.minusMonths(1).toString() }) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "Previous month",
                        tint = blue,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        activeMonth.format(monthFormatter),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                    )
                    if (activeMonth != currentMonth) {
                        Text(
                            "Today",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = blue,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xff22364d))
                                .clickable {
                                    monthText = currentMonth.toString()
                                    onSelectDay(today.toString())
                                }
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
                IconButton(
                    onClick = {
                        val next = activeMonth.plusMonths(1)
                        if (!next.isAfter(currentMonth)) monthText = next.toString()
                    },
                    enabled = activeMonth.isBefore(currentMonth),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Next month",
                        tint = if (activeMonth.isBefore(currentMonth)) blue else Color(0xff47596b),
                    )
                }
            }

            // Monthly totals strip
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MiniStat(monthSummary.learned.toString(), "Learned", mint, Modifier.weight(1f))
                MiniStat(monthSummary.reviews.toString(), "Reviews", blue, Modifier.weight(1f))
                MiniStat("${monthSummary.activeDays}d", "Active", Color.White, Modifier.weight(1f))
                MiniStat(formatStudyTime(monthSummary.minutes), "Time", amber, Modifier.weight(1f))
            }
        }

        if (viewMode == "Calendar") {
            CalendarGrid(
                weeks = grid,
                today = today,
                selectedDay = effectiveDay,
                onSelectDay = onSelectDay,
            )
            CalendarLegend()
        } else {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Hint(
                    if (tableScope == "Month") "Studied in ${activeMonth.format(monthFormatter)}"
                    else "All recorded study days",
                    modifier = Modifier.weight(1f),
                )
                ModeToggle(
                    options = listOf("Month", "All"),
                    selected = tableScope,
                    onSelect = { tableScope = it },
                )
            }
            val tableRows = remember(records, tableScope, activeMonth) {
                if (tableScope == "Month") {
                    val prefix = activeMonth.toString() + "-"
                    records.filter { it.day.startsWith(prefix) }
                } else {
                    records
                }
            }
            DailyRecordsTable(
                rows = tableRows,
                selectedDay = effectiveDay,
                onSelectDay = { day ->
                    runCatching { YearMonth.from(LocalDate.parse(day)) }.getOrNull()?.let {
                        monthText = it.toString()
                    }
                    onSelectDay(day)
                },
            )
        }

        HorizontalDivider(color = Color(0xff2c3d4f))

        // Selected day breakdown + learned words list
        SelectedDayDetail(
            day = effectiveDay,
            record = selectedRecord,
            fallbackGoal = dailyGoal,
            words = selectedDayWords,
            onOpenWord = onOpenWord,
        )
    }
}

@Composable
private fun ModeToggle(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xff111c29))
            .border(1.dp, Color(0xff2d4054), RoundedCornerShape(10.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Text(
                text = option,
                fontSize = 12.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected) Color(0xff0d1521) else faint,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) blue else Color.Transparent)
                    .clickable { onSelect(option) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}

@Composable
private fun MiniStat(value: String, label: String, accent: Color, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(Color(0xff121d2b), RoundedCornerShape(10.dp))
            .padding(horizontal = 6.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, fontSize = 11.sp, color = faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CalendarGrid(
    weeks: List<List<CalendarCell>>,
    today: LocalDate,
    selectedDay: String,
    onSelectDay: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            weekdayHeaders.forEach { dayName ->
                Text(
                    dayName,
                    Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = faint,
                )
            }
        }
        weeks.forEach { week ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                week.forEach { cell ->
                    val date = cell.date
                    if (date == null) {
                        Spacer(Modifier.weight(1f).height(48.dp))
                    } else {
                        val dayStr = date.toString()
                        val isFuture = date.isAfter(today)
                        val isToday = date == today
                        val isSelected = dayStr == selectedDay
                        val rec = cell.record
                        val learned = rec?.learned ?: 0
                        val reviews = rec?.reviews ?: 0
                        val ratio = if (rec != null && rec.target > 0) learned.toFloat() / rec.target else 0f

                        val cellBg = when {
                            isFuture -> Color(0xff101925)
                            learned == 0 && reviews == 0 -> Color(0xff121f2e)
                            learned == 0 && reviews > 0 -> Color(0xff1b2d42)
                            ratio >= 1f -> greenBg
                            ratio >= 0.5f -> Color(0xff133827)
                            else -> Color(0xff142c33)
                        }
                        val borderColor = when {
                            isSelected -> amber
                            isToday -> blue
                            rec?.goalMet == true -> green.copy(alpha = 0.75f)
                            else -> Color(0xff243446)
                        }
                        val borderWidth = if (isSelected || isToday) 1.5.dp else 1.dp

                        Column(
                            Modifier
                                .weight(1f)
                                .height(48.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(cellBg)
                                .border(borderWidth, borderColor, RoundedCornerShape(10.dp))
                                .clickable(enabled = !isFuture) { onSelectDay(dayStr) }
                                .padding(vertical = 4.dp, horizontal = 2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                date.dayOfMonth.toString(),
                                fontSize = 11.sp,
                                fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = when {
                                    isFuture -> Color(0xff4a5c6e)
                                    isSelected -> amber
                                    isToday -> blue
                                    else -> faint
                                },
                            )
                            when {
                                learned > 0 -> Text(
                                    learned.toString(),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (rec?.goalMet == true) mint else Color.White,
                                )
                                reviews > 0 -> Box(
                                    Modifier
                                        .size(6.dp)
                                        .background(blue, CircleShape)
                                )
                                else -> Text(
                                    "—",
                                    fontSize = 10.sp,
                                    color = Color(0xff3d5063),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CalendarLegend() {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Hint("Number = words learned")
        LegendChip(Color(0xff142c33), "Part")
        LegendChip(greenBg, "Goal")
        LegendChip(Color(0xff1b2d42), "Rev")
    }
}

@Composable
private fun LegendChip(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier
                .size(10.dp)
                .background(color, RoundedCornerShape(3.dp))
                .border(0.5.dp, Color(0xff4c6278), RoundedCornerShape(3.dp))
        )
        Text(label, fontSize = 11.sp, color = faint)
    }
}

@Composable
private fun DailyRecordsTable(
    rows: List<DailyRecord>,
    selectedDay: String,
    onSelectDay: (String) -> Unit,
) {
    if (rows.isEmpty()) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Color(0xff121d2b), RoundedCornerShape(12.dp))
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("No study records yet", fontWeight = FontWeight.SemiBold, color = Color.White)
            Hint("Learned words and daily reviews will appear here automatically.")
        }
        return
    }

    val totalLearned = rows.sumOf { it.learned }
    val totalNew = rows.sumOf { it.newWords }
    val totalReviews = rows.sumOf { it.reviews }
    val totalMinutes = rows.sumOf { it.minutes }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, Color(0xff2c3e52), RoundedCornerShape(12.dp))
    ) {
        // Table header
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xff121d2b))
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Date", Modifier.weight(1.7f), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = mint)
            Text("Learned", Modifier.weight(1f), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = mint, textAlign = TextAlign.End)
            Text("New", Modifier.weight(0.8f), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = mint, textAlign = TextAlign.End)
            Text("Rev", Modifier.weight(0.8f), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = mint, textAlign = TextAlign.End)
            Text("Time", Modifier.weight(0.9f), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = mint, textAlign = TextAlign.End)
        }

        // Table body rows
        rows.forEachIndexed { index, rec ->
            val isSelected = rec.day == selectedDay
            val parsed = runCatching { LocalDate.parse(rec.day) }.getOrNull()
            val dateLabel = parsed?.let {
                "${it.format(shortDayFormatter)} · ${it.format(weekdayFormatter)}"
            } ?: rec.day
            val rowBg = when {
                isSelected -> Color(0xff22364d)
                index % 2 == 0 -> Color(0xff162232)
                else -> Color(0xff131e2c)
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(rowBg)
                    .clickable { onSelectDay(rec.day) }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    dateLabel,
                    Modifier.weight(1.7f),
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) amber else Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (rec.goalMet) "${rec.learned} ✓" else rec.learned.toString(),
                    Modifier.weight(1f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (rec.goalMet) mint else Color.White,
                    textAlign = TextAlign.End,
                )
                Text(
                    rec.newWords.toString(),
                    Modifier.weight(0.8f),
                    fontSize = 13.sp,
                    color = faint,
                    textAlign = TextAlign.End,
                )
                Text(
                    rec.reviews.toString(),
                    Modifier.weight(0.8f),
                    fontSize = 13.sp,
                    color = blue,
                    textAlign = TextAlign.End,
                )
                Text(
                    formatStudyTime(rec.minutes),
                    Modifier.weight(0.9f),
                    fontSize = 12.sp,
                    color = faint,
                    textAlign = TextAlign.End,
                )
            }
        }

        // Total footer row
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xff1d2f44))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Total (${rows.size}d)",
                Modifier.weight(1.7f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Text(
                totalLearned.toString(),
                Modifier.weight(1f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = mint,
                textAlign = TextAlign.End,
            )
            Text(
                totalNew.toString(),
                Modifier.weight(0.8f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.End,
            )
            Text(
                totalReviews.toString(),
                Modifier.weight(0.8f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = blue,
                textAlign = TextAlign.End,
            )
            Text(
                formatStudyTime(totalMinutes),
                Modifier.weight(0.9f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = amber,
                textAlign = TextAlign.End,
            )
        }
    }
}

@Composable
private fun SelectedDayDetail(
    day: String,
    record: DailyRecord?,
    fallbackGoal: Int,
    words: List<Word>,
    onOpenWord: (String) -> Unit,
) {
    val parsed = runCatching { LocalDate.parse(day) }.getOrNull()
    val heading = parsed?.format(fullDayFormatter) ?: day
    val learned = record?.learned ?: 0
    val newWords = record?.newWords ?: 0
    val reviews = record?.reviews ?: 0
    val minutes = record?.minutes ?: 0
    val target = (record?.target ?: fallbackGoal).coerceAtLeast(1)
    var showAllWords by remember(day) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                heading,
                modifier = Modifier.weight(1f),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = textMain,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val badgeText = if (learned >= target) "Goal met ✓" else "$learned / $target"
            BadgePill(
                text = badgeText,
                color = if (learned >= target) greenText else faint,
                background = if (learned >= target) greenBg else Color(0xff131f2e),
            )
        }
        Progress(learned.toFloat() / target.toFloat())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MiniStat(learned.toString(), "Learned", mint, Modifier.weight(1f))
            MiniStat(newWords.toString(), "New", Color.White, Modifier.weight(1f))
            MiniStat(reviews.toString(), "Reviews", blue, Modifier.weight(1f))
            MiniStat(formatStudyTime(minutes), "Time", amber, Modifier.weight(1f))
        }
        if (words.isNotEmpty()) {
            Label("Words learned on this day (${words.size})")
            val visible = if (showAllWords) words else words.take(6)
            visible.forEach { w ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xff121d2b))
                        .clickable { onOpenWord(w.id) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(w.german, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(w.meaning, fontSize = 12.sp, color = faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(w.level, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = mint)
                }
            }
            if (words.size > 6) {
                TextButton(onClick = { showAllWords = !showAllWords }) {
                    Text(if (showAllWords) "Show fewer words" else "Show all ${words.size} words")
                }
            }
        } else {
            Hint("No newly learned words recorded on this date.")
        }
    }
}
