package dev.opentimemanager.core.backup

import dev.opentimemanager.core.model.EntryId
import dev.opentimemanager.core.model.EntrySource
import dev.opentimemanager.core.model.ProjectId
import dev.opentimemanager.core.model.Segment
import dev.opentimemanager.core.model.SegmentId
import java.security.SecureRandom
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs in its own 96 MiB Gradle test worker; see core/build.gradle.kts. */
class BackupOperationalMemoryTest {
    @Test
    fun nearFourMiBMobileContainerRoundTripsWithinConstrainedHeap() {
        val projectId = ProjectId("10000000-0000-4000-8000-000000000001")
        val entries = (1..1_500).map { index ->
            PortableEntry(
                id = EntryId(uuid(0x20000000, index)),
                projectId = projectId,
                note = "n".repeat(1_900),
                zoneId = ZoneId.of("UTC"),
                source = EntrySource.MANUAL,
            )
        }
        val segments = entries.mapIndexed { index, entry ->
            Segment(
                id = SegmentId(uuid(0x30000000, index + 1)),
                entryId = entry.id,
                projectId = projectId,
                startUtcMillis = index.toLong() * 2_000,
                durationMillis = 1_000,
            )
        }
        val snapshot = PortableSnapshot(
            projects = listOf(PortableProject(projectId, "Near limit")),
            entries = entries,
            segments = segments,
        )
        val limits = BackupLimits(maxBytes = 4 * 1024 * 1024, maxSegments = 20_000)
        val password = "constrained-worker-password".toCharArray()

        val encrypted = BackupCodec.encrypt(snapshot, password, SecureRandom(), limits)
        assertTrue("fixture should exercise at least 75% of the mobile ceiling", encrypted.size >= 3 * 1024 * 1024)
        assertTrue(encrypted.size <= limits.maxBytes)
        val restored = BackupCodec.decrypt(encrypted, password, limits)

        assertEquals(1_500, restored.entries.size)
        assertEquals(1_500, restored.segments.size)
        encrypted.fill(0)
        password.fill('\u0000')
    }

    @Test
    fun twentyThousandObjectMobileBoundaryRoundTripsWithinConstrainedHeap() {
        val projects = (1..20_000).map { index ->
            PortableProject(
                id = ProjectId(uuid(0x40000000, index)),
                name = "p",
            )
        }
        val snapshot = PortableSnapshot(projects = projects)
        val limits = BackupLimits(maxBytes = 4 * 1024 * 1024, maxSegments = 20_000)
        val password = "object-count-boundary".toCharArray()

        val encrypted = BackupCodec.encrypt(snapshot, password, SecureRandom(), limits)
        assertTrue("fixture must stay below the byte-heavy scenario", encrypted.size < 3 * 1024 * 1024)
        val restored = BackupCodec.decrypt(encrypted, password, limits)

        assertEquals(20_000, restored.projects.size)
        assertEquals("p", restored.projects.last().name)
        encrypted.fill(0)
        password.fill('\u0000')
    }

    private fun uuid(prefix: Int, suffix: Int): String = String.format(
        Locale.ROOT,
        "%08x-0000-4000-8000-%012x",
        prefix,
        suffix,
    )
}
