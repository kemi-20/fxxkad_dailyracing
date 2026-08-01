package com.fxxkad.dailyracing

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process

class BlockRecordProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        return true
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        requireRecordsUri(uri)
        enforceCaller(allowTarget = true)
        val sanitized = sanitizeValues(values)
        val id = BlockRecordStore.insert(requireNotNull(context), sanitized)
        if (id == -1L) return null
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
        requireRecordsUri(uri)
        enforceCaller(allowTarget = false)
        val limit = uri.getQueryParameter("limit")
            ?.toIntOrNull()
            ?.coerceIn(1, MAX_QUERY_LIMIT)
            ?: DEFAULT_QUERY_LIMIT
        val cursor = BlockRecordStore.query(requireNotNull(context), limit)
        cursor.setNotificationUri(requireNotNull(context).contentResolver, CONTENT_URI)
        return cursor
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        requireRecordsUri(uri)
        enforceCaller(allowTarget = false)
        val deleted = BlockRecordStore.deleteAll(requireNotNull(context))
        context?.contentResolver?.notifyChange(CONTENT_URI, null)
        return deleted
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int {
        requireRecordsUri(uri)
        enforceCaller(allowTarget = false)
        return 0
    }

    override fun getType(uri: Uri): String? = when (matcher.match(uri)) {
        RECORDS -> "vnd.android.cursor.dir/vnd.$AUTHORITY.records"
        else -> null
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        return when (method) {
            METHOD_GET_STATS -> {
                enforceCaller(allowTarget = false)
                Bundle().apply {
                    BlockRules.loadRules(ctx)
                    putLong("total_count", BlockRecordStore.getTotalCount(ctx))
                    putInt("rule_count", BlockRules.domainCount)
                }
            }
            METHOD_GET_SETTING -> {
                enforceCaller(allowTarget = true)
                Bundle().apply {
                    if (arg == SETTING_FIX_SHARE || arg == LEGACY_SETTING_FIX_QQ) {
                        putBoolean("value", BlockRecordStore.isFixShareEnabled(ctx))
                    }
                }
            }
            METHOD_SET_SETTING -> {
                enforceCaller(allowTarget = false)
                Bundle().apply {
                    if ((arg == SETTING_FIX_SHARE || arg == LEGACY_SETTING_FIX_QQ) && extras != null) {
                        val enabled = extras.getBoolean("value", true)
                        BlockRecordStore.setFixShareEnabled(ctx, enabled)
                        putBoolean("success", true)
                        putBoolean("value", enabled)
                    }
                }
            }
            else -> {
                enforceCaller(allowTarget = false)
                throw IllegalArgumentException("Unsupported method: $method")
            }
        }
    }

    private fun requireRecordsUri(uri: Uri) {
        require(matcher.match(uri) == RECORDS) { "Unsupported URI: $uri" }
    }

    private fun enforceCaller(allowTarget: Boolean) {
        val ctx = requireNotNull(context)
        val callingUid = Binder.getCallingUid()
        if (callingUid == Process.myUid()) return
        val packages = ctx.packageManager.getPackagesForUid(callingUid).orEmpty()
        if (allowTarget && BlockRules.targetPackage in packages) return
        throw SecurityException("Caller UID $callingUid is not allowed to access $AUTHORITY")
    }

    private fun sanitizeValues(values: ContentValues?): ContentValues {
        requireNotNull(values) { "Missing record values" }
        val host = DomainRules.normalizeHost(values.getAsString(COL_HOST))
            ?: throw IllegalArgumentException("Invalid host")
        val source = values.getAsString(COL_SOURCE)?.sanitize(MAX_SOURCE_LENGTH) ?: "unknown"
        val result = values.getAsString(COL_RESULT)?.sanitize(MAX_RESULT_LENGTH) ?: BlockRules.zeroAddress
        val now = System.currentTimeMillis()
        val time = values.getAsLong(COL_TIME)?.takeIf { it in 0..(now + MAX_CLOCK_SKEW_MS) } ?: now
        return ContentValues().apply {
            put(COL_TIME, time)
            put(COL_PACKAGE, BlockRules.targetPackage)
            put(COL_HOST, host)
            put(COL_SOURCE, source)
            put(COL_RESULT, result)
        }
    }

    private fun String.sanitize(maxLength: Int): String {
        return filterNot { it.isISOControl() }.take(maxLength).ifBlank { "unknown" }
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

        const val METHOD_GET_STATS = "get_stats"
        const val METHOD_GET_SETTING = "get_setting"
        const val METHOD_SET_SETTING = "set_setting"
        const val SETTING_FIX_SHARE = "fix_share"
        private const val LEGACY_SETTING_FIX_QQ = "fix_qq"

        private const val DEFAULT_QUERY_LIMIT = 200
        private const val MAX_QUERY_LIMIT = 1000
        private const val MAX_SOURCE_LENGTH = 128
        private const val MAX_RESULT_LENGTH = 64
        private const val MAX_CLOCK_SKEW_MS = 5 * 60 * 1000L

        private const val RECORDS = 1
        private val matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, "records", RECORDS)
        }
    }
}
