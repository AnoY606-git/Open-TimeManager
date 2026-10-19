package dev.opentimemanager.app.ui.screens

import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.Locales
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.then
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.opentimemanager.app.settings.ThemeMode
import dev.opentimemanager.app.tracking.AppSnapshot
import dev.opentimemanager.app.ui.AppControls
import dev.opentimemanager.app.ui.AppUiState
import dev.opentimemanager.app.ui.ReportPeriod
import dev.opentimemanager.app.ui.theme.OpenTimeManagerTheme
import dev.opentimemanager.core.model.TrackingSession
import java.time.LocalDate
import java.time.ZoneId
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MonthlyReportUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun previousArrowCannotSelectAnUnsupportedYear() {
        compose.setContent {
            OpenTimeManagerTheme(ThemeMode.LIGHT, false) {
                MonthReportNavigation(YearMonth.of(1900, 1), LocalDate.of(1900, 1, 15), ZoneId.of("UTC")) {}
            }
        }
        compose.onNodeWithTag("report_month_previous").assertIsNotEnabled()
    }

    @Test fun monthlyPlanDraftRestoresForItsOpeningMonthNotTheNewlySelectedMonth() {
        val original = YearMonth.of(2026, 9)
        val current = androidx.compose.runtime.mutableStateOf(AppUiState(
            snapshot = AppSnapshot(TrackingSession.idle(), emptyList(), emptyList(), emptyList()),
            controls = AppControls(reportPeriod = ReportPeriod.CalendarMonth(original)),
        ))
        var saved: Pair<YearMonth, Int?>? = null
        val restorer = StateRestorationTester(compose)
        restorer.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.Locales(LocaleList(Locale("en")))) {
                OpenTimeManagerTheme(ThemeMode.LIGHT, false) {
                    ReportsScreen(current.value, {}, onMonthlyTargetChange = { month, minutes -> saved = month to minutes })
                }
            }
        }
        compose.onNodeWithTag("reports_list").performScrollToNode(hasTestTag("monthly_target_edit"))
        compose.onNodeWithTag("monthly_target_edit").performClick()
        compose.onNodeWithTag("monthly_target_hours").performTextReplacement("168")
        compose.onNodeWithTag("monthly_target_minutes").performTextReplacement("30")
        compose.runOnIdle { current.value = current.value.copy(controls = AppControls(reportPeriod = ReportPeriod.MONTH)) }
        restorer.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("monthly_target_hours").assertTextContains("168")
        compose.onNodeWithTag("monthly_target_minutes").assertTextContains("30")
        compose.onNodeWithTag("monthly_target_save").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(original to 10_110, saved) }
    }

    @Test fun missingPlanDoesNotClaimThatHoursAreMissing() {
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.Locales(LocaleList(Locale("en")))) {
                OpenTimeManagerTheme(ThemeMode.LIGHT, false) {
                    MonthlyBalanceCard(0, null, loading = false, finished = true, canEdit = true, onEdit = {})
                }
            }
        }
        compose.onNodeWithTag("monthly_delta").assertDoesNotExist()
        compose.onNodeWithText("Plan not set").assertIsDisplayed()
    }

    @Test fun previousMonthIsOneActionAwayWithoutEnteringDates() {
        var selected: ReportPeriod? = null
        val today = LocalDate.now()
        compose.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(412.dp, 915.dp)) then
                    DeviceConfigurationOverride.Locales(LocaleList(Locale("en"))),
            ) {
                OpenTimeManagerTheme(ThemeMode.LIGHT, false) {
                    ReportsScreen(
                        state = AppUiState(
                            snapshot = AppSnapshot(TrackingSession.idle(), emptyList(), emptyList(), emptyList()),
                            controls = AppControls(reportPeriod = ReportPeriod.WEEK),
                        ),
                        onSelectPeriod = { selected = it },
                    )
                }
            }
        }
        compose.onNodeWithTag("report_month_last").assertIsDisplayed().performClick()
        compose.runOnIdle {
            val bounds = requireNotNull(selected).bounds(today, ZoneId.systemDefault())
            assertEquals(today.withDayOfMonth(1).minusMonths(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(), bounds.fromUtcMillis)
            assertEquals(today.withDayOfMonth(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(), bounds.toUtcMillis)
        }
    }

    @Test fun previousArrowCrossesTheYearWithoutManualDateInput() {
        var selected: ReportPeriod? = null
        compose.setContent {
            OpenTimeManagerTheme(ThemeMode.LIGHT, false) {
                MonthReportNavigation(YearMonth.of(2026, 1), LocalDate.of(2026, 1, 15), ZoneId.of("UTC")) { selected = it }
            }
        }
        compose.onNodeWithTag("report_month_previous").performClick()
        compose.runOnIdle { assertEquals(ReportPeriod.CalendarMonth(YearMonth.of(2025, 12)), selected) }
    }

    @Test fun unsupportedMonthNeverLabelsAWeeklyFallbackAsMonthlyBalance() {
        compose.setContent {
            OpenTimeManagerTheme(ThemeMode.LIGHT, false) {
                ReportsScreen(
                    AppUiState(
                        snapshot = AppSnapshot(TrackingSession.idle(), emptyList(), emptyList(), emptyList()),
                        controls = AppControls(reportPeriod = ReportPeriod.CalendarMonth(YearMonth.of(9999, 12))),
                    ), {},
                )
            }
        }
        compose.onNodeWithTag("monthly_balance_card").assertDoesNotExist()
    }

    @Test fun loadingNeverShowsThePreviousMonthsActualOrDifference() {
        compose.setContent {
            OpenTimeManagerTheme(ThemeMode.LIGHT, false) {
                MonthlyBalanceCard(999_000, 60, loading = true, finished = true, canEdit = true, onEdit = {})
            }
        }
        compose.onNodeWithTag("monthly_actual").assertDoesNotExist()
        compose.onNodeWithTag("monthly_delta").assertDoesNotExist()
    }
}
