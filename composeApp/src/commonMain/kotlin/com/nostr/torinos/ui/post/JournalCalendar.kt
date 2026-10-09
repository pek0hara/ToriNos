package com.nostr.torinos.ui.post

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nostr.torinos.journal.monthEnd
import com.nostr.torinos.journal.plusDays
import kotlinx.datetime.LocalDate

@Composable
internal fun JournalCalendarHeader(
    state: JournalState,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onToggleCalendar: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPreviousMonth) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "前月",
            )
        }
        Text(
            text = "${state.selectedMonth.year}年${state.selectedMonth.month.ordinal + 1}月",
            modifier = Modifier.clickable(onClick = onToggleCalendar),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        IconButton(
            onClick = onNextMonth,
            enabled = state.canGoNextMonth,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "翌月",
            )
        }
    }
}

/**
 * [collapsed]なら選択日を含む週だけを出し、下の取っ手で月全体へ戻す。
 * 選択週は置いたまま、ほかの週を縦に縮めて消すことで、月が選択週へ畳まれるように見せる。
 */
@Composable
internal fun JournalCalendarGrid(
    state: JournalState,
    collapsed: Boolean,
    onSelectDate: (LocalDate) -> Unit,
    onExpand: () -> Unit,
) {
    val allWeeks = remember(state.selectedMonth) { calendarWeeks(state.selectedMonth) }
    val keptWeek = calendarWeekContaining(allWeeks, state.selectedDate)

    // 縮めきった週も子として残るため、spacedByではなく各週の上余白で間隔を取る。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            listOf("日", "月", "火", "水", "木", "金", "土").forEach { label ->
                Text(
                    text = label,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                )
            }
        }

        allWeeks.forEach { week ->
            AnimatedVisibility(
                visible = !collapsed || week == keptWeek,
                enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    week.forEach { date ->
                        if (date == null) {
                            Box(modifier = Modifier.weight(1f).height(32.dp))
                        } else {
                            JournalCalendarDay(
                                date = date,
                                selected = date == state.selectedDate,
                                isToday = date == state.today,
                                isFuture = date > state.today,
                                entryCount = state.entryCountsByDate[date] ?: 0,
                                onClick = { onSelectDate(date) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    repeat(7 - week.size) {
                        Box(modifier = Modifier.weight(1f).height(32.dp))
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = collapsed,
            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 28.dp)
                    .clickable(onClickLabel = "カレンダーを広げる", onClick = onExpand),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.ExpandMore,
                    contentDescription = "カレンダーを広げる",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 日曜始まりの週ごとの日付。月初より前の枠は null。 */
internal fun calendarWeeks(month: LocalDate): List<List<LocalDate?>> {
    val leadingBlankDays = (month.dayOfWeek.ordinal + 1) % 7
    val days = generateSequence(month) { it.plusDays(1) }
        .takeWhile { it <= month.monthEnd() }
        .toList()
    val cells = List(leadingBlankDays) { null } + days
    return cells.chunked(7)
}

/** [weeks]のうち[date]を含む週。月外の日なら最初の週。 */
internal fun calendarWeekContaining(weeks: List<List<LocalDate?>>, date: LocalDate): List<LocalDate?> =
    weeks.firstOrNull { date in it } ?: weeks.first()

@Composable
private fun JournalCalendarDay(
    date: LocalDate,
    selected: Boolean,
    isToday: Boolean,
    isFuture: Boolean,
    entryCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = MaterialTheme.shapes.small
    val colorScheme = MaterialTheme.colorScheme
    val entryIntensity = calendarEntryIntensity(entryCount)
    // 選択はヒートマップと同系色だと埋もれるため、背景色ではなく太い枠と太字で示す
    val backgroundColor = if (entryCount == 0) {
        colorScheme.surface
    } else {
        lerp(colorScheme.surface, colorScheme.primary, entryIntensity)
    }
    val borderColor = when {
        selected -> colorScheme.onSurface
        isFuture -> Color.Transparent
        else -> colorScheme.outlineVariant
    }
    val contentColor = when {
        isFuture -> colorScheme.onSurface.copy(alpha = CalendarFutureDayAlpha)
        entryIntensity >= CalendarEntryHighContrastThreshold -> colorScheme.onPrimary
        isToday -> colorScheme.primary
        else -> colorScheme.onSurface
    }

    Box(
        // 文字を大きくしたときは日付の下に今日のドットの場所を残したまま縦に伸ばす。
        modifier = modifier
            .heightIn(min = 32.dp)
            .clip(shape)
            .background(backgroundColor)
            .border(if (selected) 2.dp else 1.dp, borderColor, shape)
            .clickable(enabled = !isFuture, onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = date.day.toString(),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (selected || isToday) FontWeight.Bold else null,
            color = contentColor,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        if (isToday) {
            // 今日は枠ではなく数字下のドットで示し、選択枠と競合させない
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(contentColor),
            )
        }
    }
}

private fun calendarEntryIntensity(entryCount: Int): Float {
    if (entryCount <= 0) return 0f
    val clampedCount = entryCount.coerceIn(1, CalendarEntryMaxGradientCount)
    val step = (clampedCount - 1).toFloat() / (CalendarEntryMaxGradientCount - 1)
    return CalendarEntryMinIntensity + (CalendarEntryMaxIntensity - CalendarEntryMinIntensity) * step
}

private const val CalendarEntryMaxGradientCount = 10
private const val CalendarEntryMinIntensity = 0.16f
private const val CalendarEntryMaxIntensity = 0.82f
private const val CalendarEntryHighContrastThreshold = 0.62f
private const val CalendarFutureDayAlpha = 0.38f
