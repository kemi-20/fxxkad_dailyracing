package com.fxxkad.dailyracing

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

object BlockRecordStore {
    const val TABLE_RECORDS = "records"
    const val COL_ID = "_id"
    const val COL_TIME = "time"
    const val COL_PACKAGE = "package_name"
    const val COL_HOST = "host"
    const val COL_SOURCE = "source"
    const val COL_RESULT = "result"

    private const val MAX_RECORDS = 1000
    private const val PREFS_NAME = "block_stats"
    const val PREF_TOTAL_COUNT = "total_count"
    const val PREF_FIX_QQ = "fix_qq"

    fun getTotalCount(context: Context): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(PREF_TOTAL_COUNT, 0L)
    }

    private fun incrementTotalCount(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getLong(PREF_TOTAL_COUNT, 0L)
        prefs.edit().putLong(PREF_TOTAL_COUNT, current + 1).apply()
    }

    fun isFixQqEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_FIX_QQ, true)
    }

    fun setFixQqEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_FIX_QQ, enabled).apply()
    }

    fun insert(context: Context, values: ContentValues): Long {
        val db = DatabaseHelper(context.applicationContext).writableDatabase
        val id = db.insert(TABLE_RECORDS, null, values)
        if (id != -1L) {
            incrementTotalCount(context)
        }
        trimRecords(db)
        return id
    }

    fun query(context: Context, limit: String): Cursor {
        return DatabaseHelper(context.applicationContext).readableDatabase.query(
            TABLE_RECORDS,
            null,
            null,
            null,
            null,
            null,
            "$COL_ID DESC",
            limit
        )
    }

    fun deleteAll(context: Context): Int {
        return DatabaseHelper(context.applicationContext).writableDatabase.delete(TABLE_RECORDS, null, null)
    }

    private fun trimRecords(db: SQLiteDatabase) {
        db.execSQL(
            "DELETE FROM $TABLE_RECORDS WHERE $COL_ID NOT IN " +
                "(SELECT $COL_ID FROM $TABLE_RECORDS ORDER BY $COL_ID DESC LIMIT $MAX_RECORDS)"
        )
    }

    private class DatabaseHelper(context: Context) :
        SQLiteOpenHelper(context, "block_records.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $TABLE_RECORDS (" +
                    "$COL_ID INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "$COL_TIME INTEGER NOT NULL," +
                    "$COL_PACKAGE TEXT NOT NULL," +
                    "$COL_HOST TEXT NOT NULL," +
                    "$COL_SOURCE TEXT NOT NULL," +
                    "$COL_RESULT TEXT NOT NULL" +
                    ")"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_RECORDS")
            onCreate(db)
        }
    }
}
