package dev.opentimemanager.app.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.opentimemanager.core.model.EntryId
import dev.opentimemanager.core.model.ProjectId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ManualProjectPickerRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: ProjectRepository

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        repository = ProjectRepository(database, Dispatchers.IO)
    }

    @After fun tearDown() = database.close()

    @Test fun projectBeyondFirstHundredCanBeSearchedAndUsedByHistorySave() = runBlocking {
        insertProjects(150)

        val page = repository.manualEntryPage(
            query = "Project 001",
            cursor = null,
            pinnedProjectId = null,
            pageSize = 20,
            displayName = ProjectEntity::name,
        )
        val selected = page.projects.single { it.name == "Project 001" }

        assertEquals(
            EditResult.Applied,
            CompletedEntryRepository(database, Dispatchers.IO).save(
                EntryId("manual-entry"), ProjectId(selected.id), "", 1_000, 60_000, null,
            ),
        )
        assertEquals(
            selected.id,
            withContext(Dispatchers.IO) { database.trackingDao().entry("manual-entry")?.projectId },
        )
    }

    @Test fun archivedCurrentProjectIsPinnedWhenItIsOutsideTheActivePageAndSearch() = runBlocking {
        insertProjects(150)
        val archived = withContext(Dispatchers.IO) {
            database.projectDao().project("project-00149")!!
        }
        assertEquals(
            EditResult.Applied,
            CompletedEntryRepository(database, Dispatchers.IO).save(
                EntryId("archived-entry"), ProjectId(archived.id), "before", 0, 1, null,
            ),
        )
        withContext(Dispatchers.IO) { database.projectDao().archive(archived.id, 500) }

        val page = repository.manualEntryPage(
            query = "no-match",
            cursor = null,
            pinnedProjectId = archived.id,
            pageSize = 20,
            displayName = ProjectEntity::name,
        )

        assertTrue(page.projects.isEmpty())
        assertEquals(archived.id, page.pinnedProject?.id)
        assertEquals(500L, page.pinnedProject?.archivedAtUtcMillis)
        assertNull(page.nextCursor)
        assertEquals(
            EditResult.Applied,
            CompletedEntryRepository(database, Dispatchers.IO).updateMetadata(
                EntryId("archived-entry"), ProjectId(archived.id), "after", expectedRevision = 0,
            ),
        )
    }

    @Test fun twentyThousandProjectsKeepInitialPageAndEachSubsequentPageBounded() = runBlocking {
        insertProjects(20_000)

        val first = repository.manualEntryPage(
            query = "",
            cursor = null,
            pinnedProjectId = "project-00000",
            pageSize = 50,
            displayName = ProjectEntity::name,
        )
        val second = repository.manualEntryPage(
            query = "",
            cursor = first.nextCursor,
            pinnedProjectId = "project-00000",
            pageSize = 50,
            displayName = ProjectEntity::name,
        )

        assertEquals(50, first.projects.size)
        assertEquals(50, second.projects.size)
        assertEquals(1, listOfNotNull(first.pinnedProject).size)
        assertTrue(first.hasMore)
        assertTrue(second.hasMore)
        assertFalse(first.projects.map(ProjectEntity::id).intersect(second.projects.map(ProjectEntity::id).toSet()).isNotEmpty())
        assertTrue((first.projects + listOfNotNull(first.pinnedProject)).size <= 51)
        assertTrue((second.projects + listOfNotNull(second.pinnedProject)).size <= 51)
        val plan = database.openHelper.readableDatabase.query(
            """EXPLAIN QUERY PLAN SELECT * FROM projects
                WHERE archivedAtUtcMillis IS NULL
                ORDER BY createdAtUtcMillis DESC, id DESC LIMIT 50
            """.trimIndent(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("detail")))
            }
        }
        assertTrue(plan.any { it.contains("index_projects_archivedAtUtcMillis_createdAtUtcMillis_id") })
    }

    private fun insertProjects(count: Int) {
        database.openHelper.writableDatabase.beginTransaction()
        try {
            repeat(count) { index ->
                database.openHelper.writableDatabase.execSQL(
                    "INSERT INTO projects VALUES (?, ?, 0, ?, NULL)",
                    arrayOf<Any?>(
                        "project-${index.toString().padStart(5, '0')}",
                        "Project ${index.toString().padStart(3, '0')}",
                        index.toLong(),
                    ),
                )
            }
            database.openHelper.writableDatabase.setTransactionSuccessful()
        } finally {
            database.openHelper.writableDatabase.endTransaction()
        }
    }
}
