package dev.opentimemanager.app.ui

import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class MonthlyBalanceTest {
    @Test fun weekdaySuggestionUsesTheSelectedCalendarMonthAndDailyGoal() {
        assertEquals(10_560, suggestedMonthlyTargetMinutes(YearMonth.of(2026, 9), 480))
        assertEquals(11_040, suggestedMonthlyTargetMinutes(YearMonth.of(2026, 7), 480))
        assertEquals(10_080, suggestedMonthlyTargetMinutes(YearMonth.of(2024, 2), 480))
        assertEquals(9_600, suggestedMonthlyTargetMinutes(YearMonth.of(2025, 2), 480))
        assertEquals(5_280, suggestedMonthlyTargetMinutes(YearMonth.of(2026, 9), 240))
    }

    @Test fun missingTargetDoesNotCreateDebtOrProgress() {
        val balance = monthlyBalance(3_600_000L, null)
        assertNull(balance.deltaMillis)
        assertNull(balance.progress)
    }

    @Test fun balanceKeepsTheSignAndExactSubMinuteDifference() {
        assertEquals(-30_000L, monthlyBalance(3_570_000L, 60).deltaMillis)
        assertEquals(0L, monthlyBalance(3_600_000L, 60).deltaMillis)
        assertEquals(30_000L, monthlyBalance(3_630_000L, 60).deltaMillis)
        assertEquals(0.5f, monthlyBalance(1_800_000L, 60).progress!!, 0f)
        assertEquals(1f, monthlyBalance(7_200_000L, 60).progress!!, 0f)
    }

    @Test fun zeroTargetIsExplicitAndNeverDividesByZero() {
        assertEquals(0L, monthlyBalance(0L, 0).deltaMillis)
        assertNull(monthlyBalance(0L, 0).progress)
        assertEquals(30_000L, monthlyBalance(30_000L, 0).deltaMillis)
        assertNull(monthlyBalance(30_000L, 0).progress)
    }

    @Test fun targetConversionDoesNotOverflowIntegerArithmetic() {
        assertEquals(-2_678_400_000L, monthlyBalance(0L, 44_640).deltaMillis)
        assertEquals(Long.MAX_VALUE - 2_678_400_000L, monthlyBalance(Long.MAX_VALUE, 44_640).deltaMillis)
    }

    @Test fun balanceRejectsNegativeInputsInsteadOfReturningMisleadingTotals() {
        expectInvalid { monthlyBalance(-1L, 60) }
        expectInvalid { monthlyBalance(0L, -1) }
    }

    @Test fun inputAcceptsWholeHoursMinutesZeroAndLocalizedDigits() {
        val september = YearMonth.of(2026, 9)
        assertEquals(10_110, validateMonthlyTargetInput(september, "168", "30"))
        assertEquals(0, validateMonthlyTargetInput(september, "0", "0"))
        assertEquals(10_110, validateMonthlyTargetInput(september, " 168 ", "30"))
        assertEquals(10_110, validateMonthlyTargetInput(september, "١٦٨", "٣٠"))
        assertEquals(44_640, validateMonthlyTargetInput(YearMonth.of(2026, 7), "744", "0"))
        assertEquals(41_760, validateMonthlyTargetInput(YearMonth.of(2024, 2), "696", "0"))
    }

    @Test fun inputRejectsIncompleteSignedDecimalAndOutOfMonthValues() {
        val september = YearMonth.of(2026, 9)
        for ((hours, minutes) in listOf(
            "" to "0", "1" to "", "-1" to "0", "+1" to "0", "1.5" to "0", "1,5" to "0",
            "1" to "60", "1" to "-1", "745" to "0", "720" to "1", "999999999999999999" to "0",
        )) {
            assertNull("Нельзя принять $hours ч $minutes мин", validateMonthlyTargetInput(september, hours, minutes))
        }
        assertNull(validateMonthlyTargetInput(YearMonth.of(2025, 2), "673", "0"))
        assertNull(validateMonthlyTargetInput(YearMonth.of(1899, 12), "1", "0"))
        assertNull(validateMonthlyTargetInput(YearMonth.of(10_000, 1), "1", "0"))
    }

    @Test fun deltaFormattingPreservesSignSecondsAndDurationsAboveTwentyFourHours() {
        assertEquals("00:00:00", formatMonthlyBalanceDelta(0L))
        assertEquals("+00:00:30", formatMonthlyBalanceDelta(30_000L))
        assertEquals("−00:00:30", formatMonthlyBalanceDelta(-30_000L))
        assertEquals("+176:30:00", formatMonthlyBalanceDelta(635_400_000L))
        assertEquals("−176:30:00", formatMonthlyBalanceDelta(-635_400_000L))
        assertEquals("−2562047788015:12:55", formatMonthlyBalanceDelta(Long.MIN_VALUE))
    }

    private fun expectInvalid(action: () -> Unit) {
        try {
            action()
            fail("Недопустимое значение было принято")
        } catch (_: IllegalArgumentException) {
            // Предусловие отклоняет ошибочные данные до расчёта.
        }
    }
}
