package dev.opentimemanager.app.ui

import dev.opentimemanager.app.settings.UserSettings
import dev.opentimemanager.app.settings.maximumMonthlyTargetMinutesOrNull
import java.time.DayOfWeek
import java.time.YearMonth
import java.util.Locale
import kotlin.math.abs

data class MonthlyBalance(val deltaMillis: Long?, val progress: Float?)

/** Только предложение для пятидневки: праздники, отпуска и индивидуальный график неизвестны. */
fun suggestedMonthlyTargetMinutes(month: YearMonth, dailyGoalMinutes: Int): Int {
    requireNotNull(maximumMonthlyTargetMinutesOrNull(month)) { "Unsupported target month" }
    require(dailyGoalMinutes in UserSettings.MIN_GOAL..UserSettings.MAX_GOAL)
    val weekdays = (1..month.lengthOfMonth()).count { day ->
        val weekday = month.atDay(day).dayOfWeek
        weekday != DayOfWeek.SATURDAY && weekday != DayOfWeek.SUNDAY
    }
    return weekdays * dailyGoalMinutes
}

/** Отсутствие нормы не означает долг; при нулевой норме отношение не определено. */
fun monthlyBalance(actualMillis: Long, targetMinutes: Int?): MonthlyBalance {
    require(actualMillis >= 0L)
    require(targetMinutes == null || targetMinutes >= 0)
    if (targetMinutes == null) return MonthlyBalance(null, null)
    val targetMillis = targetMinutes.toLong() * 60_000L
    return MonthlyBalance(
        deltaMillis = Math.subtractExact(actualMillis, targetMillis),
        progress = if (targetMillis == 0L) null else
            (actualMillis.toDouble() / targetMillis).coerceIn(0.0, 1.0).toFloat(),
    )
}

fun validateMonthlyTargetInput(month: YearMonth, hours: String, minutes: String): Int? {
    val maximum = maximumMonthlyTargetMinutesOrNull(month) ?: return null
    fun wholeNumber(input: String): Int? = input.trim()
        .takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull()
    val wholeHours = wholeNumber(hours)?.takeIf { it in 0..744 } ?: return null
    val wholeMinutes = wholeNumber(minutes)?.takeIf { it in 0..59 } ?: return null
    return (wholeHours * 60 + wholeMinutes).takeIf { it <= maximum }
}

fun formatMonthlyBalanceDelta(delta: Long): String {
    val sign = when {
        delta > 0L -> "+"
        delta < 0L -> "−"
        else -> ""
    }
    // Деление перед abs безопасно даже для Long.MIN_VALUE и не ограничивает часы сутками.
    val seconds = abs(delta / 1_000L)
    return String.format(Locale.ROOT, "%s%02d:%02d:%02d", sign, seconds / 3_600L, seconds / 60L % 60L, seconds % 60L)
}
