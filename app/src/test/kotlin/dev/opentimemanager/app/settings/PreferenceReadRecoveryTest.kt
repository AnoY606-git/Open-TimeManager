package dev.opentimemanager.app.settings

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class PreferenceReadRecoveryTest {
    private val theme = stringPreferencesKey("theme")
    private val dailyGoal = intPreferencesKey("daily_goal_minutes")
    private val preferredProject = stringPreferencesKey("default_project_id")

    @Test fun ioFailurePublishesFallbackThenCurrentAndFutureValuesToTheSameSubscriber() = runBlocking {
        var attempts = 0
        val waits = mutableListOf<Long>()
        val current = preferencesOf(theme to ThemeMode.DARK.name, dailyGoal to 600)
        val updated = preferencesOf(theme to ThemeMode.LIGHT.name, dailyGoal to 720)
        val observed = flow {
            attempts++
            if (attempts == 1) throw IOException("Temporary read failure")
            emit(current)
            emit(updated)
        }.recoverPreferenceReadFailures { waits += it }.toList()

        assertEquals(listOf(emptyPreferences(), current, updated), observed)
        assertEquals(2, attempts)
        assertEquals(listOf(100L), waits)
    }

    @Test fun preferredProjectRecoversFromNullWithoutConsumerResubscription() = runBlocking {
        var attempts = 0
        val projectId = "50000000-0000-4000-8000-000000000001"
        val observed = flow {
            attempts++
            if (attempts == 1) throw IOException("Temporary preferred-project read failure")
            emit(preferencesOf(preferredProject to projectId))
        }.recoverPreferenceReadFailures {}
            .map { canonicalDefaultProjectIdOrNull(it[preferredProject]) }
            .toList()

        assertEquals(listOf(null, projectId), observed)
        assertEquals(2, attempts)
    }

    @Test fun repeatedFailuresUseExponentialBackoffCappedAtFiveSeconds() = runBlocking {
        var attempts = 0
        val waits = mutableListOf<Long>()
        val valid = preferencesOf(theme to ThemeMode.DARK.name)
        val observed = flow {
            attempts++
            if (attempts <= 10) throw IOException("Still unavailable")
            emit(valid)
        }.recoverPreferenceReadFailures { waits += it }.toList()

        assertEquals(listOf(100L, 200L, 400L, 800L, 1_600L, 3_200L, 5_000L, 5_000L, 5_000L, 5_000L), waits)
        assertEquals(11, attempts)
        assertEquals(valid, observed.last())
        assertTrue(observed.dropLast(1).all { it == emptyPreferences() })
    }

    @Test fun aSuccessfulReadResetsBackoffBeforeTheNextFailure() = runBlocking {
        var attempts = 0
        val waits = mutableListOf<Long>()
        val healthy = preferencesOf(theme to ThemeMode.DARK.name)
        val recovered = preferencesOf(theme to ThemeMode.LIGHT.name)
        val observed = flow {
            attempts++
            when (attempts) {
                1, 2 -> throw IOException("Unavailable")
                3 -> {
                    emit(healthy)
                    throw IOException("Unavailable again")
                }
                else -> emit(recovered)
            }
        }.recoverPreferenceReadFailures { waits += it }.toList()

        assertEquals(listOf(100L, 200L, 100L), waits)
        assertEquals(listOf(emptyPreferences(), emptyPreferences(), healthy, emptyPreferences(), recovered), observed)
    }

    @Test fun healthyFlowDoesNotScheduleRetriesOrRestartOnNormalCompletion() = runBlocking {
        var attempts = 0
        val valid = preferencesOf(theme to ThemeMode.DARK.name)
        val observed = flow {
            attempts++
            emit(valid)
        }.recoverPreferenceReadFailures { fail("Healthy reads must not start a retry timer") }.toList()

        assertEquals(listOf(valid), observed)
        assertEquals(1, attempts)
    }

    @Test fun nonIoFailuresPropagateWithoutFallbackOrRetry() = runBlocking {
        val failure = IllegalStateException("Programming error")
        var attempts = 0
        val observed = mutableListOf<Preferences>()
        try {
            flow<Preferences> {
                attempts++
                throw failure
            }.recoverPreferenceReadFailures { fail("Non-I/O failure must not retry") }.toList(observed)
            fail("Expected the original non-I/O failure")
        } catch (error: IllegalStateException) {
            assertSame(failure, error)
        }
        assertTrue(observed.isEmpty())
        assertEquals(1, attempts)
    }

    @Test fun upstreamCancellationPropagatesWithoutFallbackOrRetry() = runBlocking {
        val cancellation = CancellationException("Collector cancelled")
        val observed = mutableListOf<Preferences>()
        try {
            flow<Preferences> { throw cancellation }
                .recoverPreferenceReadFailures { fail("Cancellation must not retry") }
                .toList(observed)
            fail("Expected cancellation")
        } catch (error: CancellationException) {
            assertSame(cancellation, error)
        }
        assertTrue(observed.isEmpty())
    }

    @Test fun cancellingDuringBackoffStopsRecoveryWithoutAnotherRead() = runBlocking {
        var attempts = 0
        val enteredBackoff = CompletableDeferred<Long>()
        val observed = mutableListOf<Preferences>()
        val collector = launch {
            flow<Preferences> {
                attempts++
                throw IOException("Unavailable")
            }.recoverPreferenceReadFailures {
                enteredBackoff.complete(it)
                awaitCancellation()
            }.collect { observed += it }
        }
        try {
            assertEquals(100L, withTimeout(5_000) { enteredBackoff.await() })
        } finally {
            collector.cancelAndJoin()
        }
        assertTrue(collector.isCancelled)
        assertEquals(1, attempts)
        assertEquals(listOf(emptyPreferences()), observed)
    }
}
