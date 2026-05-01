package com.fxxkad.dailyracing

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.net.Uri

class BlockRecordProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        return true
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        if (matcher.match(uri) != RECORDS) return null
        val id = BlockRecordStore.insert(requireNotNull(context), values ?: ContentValues())
        context?.contentResolver?.notifyChange(CONTENT_URI, null)
        return ContentUris.withAppendedId(CONTENT_URI, id)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        if (matcher.match(uri) != RECORDS) return null
        val limit = uri.getQueryParameter("limit") ?: "200"
        val cursor = BlockRecordStore.query(requireNotNull(context), limit)
        cursor.setNotificationUri(requireNotNull(context).contentResolver, CONTENT_URI)
        return cursor
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        if (matcher.match(uri) != RECORDS) return 0
        val deleted = BlockRecordStore.deleteAll(requireNotNull(context))
        context?.contentResolver?.notifyChange(CONTENT_URI, null)
        return deleted
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    override fun getType(uri: Uri): String? = when (matcher.match(uri)) {
        RECORDS -> "vnd.android.cursor.dir/vnd.$AUTHORITY.records"
        else -> null
    }

    companion object {
        const val AUTHORITY = "com.fxxkad.dailyracing.records"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/records")

        const val TABLE_RECORDS = BlockRecordStore.TABLE_RECORDS
        const val COL_ID = BlockRecordStore.COL_ID
        const val COL_TIME = BlockRecordStore.COL_TIME
        const val COL_PACKAGE = BlockRecordStore.COL_PACKAGE
        const val COL_HOST = BlockRecordStore.COL_HOST
        const val COL_SOURCE = BlockRecordStore.COL_SOURCE
        const val COL_RESULT = BlockRecordStore.COL_RESULT

        private const val RECORDS = 1
        private val matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, "records", RECORDS)
        }
    }
}
