package dev.opentimemanager.app.dataexport

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.ComponentName
import android.content.Context
import android.content.IntentFilter
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import androidx.test.core.app.ApplicationProvider
import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.android.controller.ContentProviderController

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class ResolverDocumentStoreTest {
    private lateinit var context: Context
    private lateinit var documentsProvider: TestDocumentsProvider
    private lateinit var plainProvider: TestPlainProvider

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val documentsInfo = providerInfo(
            TestDocumentsProvider::class.java,
            TestDocumentsProvider.AUTHORITY,
            exported = true,
        )
        shadowOf(context.packageManager).addOrUpdateProvider(documentsInfo)
        documentsProvider = ContentProviderController.of(TestDocumentsProvider())
            .create(documentsInfo)
            .get()
        shadowOf(context.packageManager).addIntentFilterForProvider(
            ComponentName(context.packageName, TestDocumentsProvider::class.java.name),
            IntentFilter(DocumentsContract.PROVIDER_INTERFACE),
        )
        plainProvider = ContentProviderController.of(TestPlainProvider())
            .create(providerInfo(TestPlainProvider::class.java, TestPlainProvider.AUTHORITY, exported = false))
            .get()
    }

    @Test
    fun documentUriDeletionUsesDocumentsContractProviderMethod() {
        val uri = DocumentsContract.buildDocumentUri(TestDocumentsProvider.AUTHORITY, "new-partial")

        assertTrue(DocumentsContract.isDocumentUri(context, uri))
        assertTrue(ResolverDocumentStore(context).delete(uri))

        assertEquals(listOf("new-partial"), documentsProvider.deleted)
    }

    @Test
    fun nonDocumentUriDeletionUsesResolverFallback() {
        val uri = Uri.parse("content://${TestPlainProvider.AUTHORITY}/temporary/new-partial")

        assertTrue(ResolverDocumentStore(context).delete(uri))

        assertEquals(listOf(uri), plainProvider.deleted)
    }

    private fun providerInfo(
        type: Class<out ContentProvider>,
        authority: String,
        exported: Boolean,
    ) = ProviderInfo().apply {
        name = type.name
        packageName = context.packageName
        applicationInfo = context.applicationInfo
        this.authority = authority
        this.exported = exported
        grantUriPermissions = true
        if (DocumentsProvider::class.java.isAssignableFrom(type)) {
            readPermission = Manifest.permission.MANAGE_DOCUMENTS
            writePermission = Manifest.permission.MANAGE_DOCUMENTS
        }
    }
}

class TestDocumentsProvider : DocumentsProvider() {
    val deleted = mutableListOf<String>()
    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: emptyArray())

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: emptyArray())

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor = MatrixCursor(projection ?: emptyArray())

    @Throws(FileNotFoundException::class)
    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor = throw FileNotFoundException(documentId)

    override fun deleteDocument(documentId: String) {
        deleted += documentId
    }

    companion object {
        const val AUTHORITY = "dev.opentimemanager.test.documents"
    }
}

class TestPlainProvider : ContentProvider() {
    val deleted = mutableListOf<Uri>()
    override fun onCreate(): Boolean = true
    override fun getType(uri: Uri): String? = null
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        deleted += uri
        return 1
    }

    companion object {
        const val AUTHORITY = "dev.opentimemanager.test.plain"
    }
}
