package dev.opentimemanager.app.ui

import android.content.Context
import androidx.annotation.StringRes
import dev.opentimemanager.app.R

enum class LegalDocument(
    val assetPath: String,
    @param:StringRes val titleResource: Int,
) {
    LICENSE("legal/LICENSE.txt", R.string.source_license),
    NOTICE("legal/NOTICE.txt", R.string.project_notice),
    THIRD_PARTY_NOTICES("legal/THIRD_PARTY_NOTICES.md", R.string.third_party_notices),
}

class LegalDocumentRepository(context: Context) {
    private val assets = context.applicationContext.assets

    fun read(document: LegalDocument): String =
        assets.open(document.assetPath).bufferedReader(Charsets.UTF_8).use { it.readText() }
}
