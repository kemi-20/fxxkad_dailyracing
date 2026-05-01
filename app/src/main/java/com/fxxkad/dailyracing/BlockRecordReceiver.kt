package com.fxxkad.dailyracing

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent

class BlockRecordReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RECORD_BLOCK) return
        val host = intent.getStringExtra(EXTRA_HOST) ?: return
        val values = ContentValues().apply {
            put(BlockRecordStore.COL_TIME, intent.getLongExtra(EXTRA_TIME, System.currentTimeMillis()))
            put(BlockRecordStore.COL_PACKAGE, intent.getStringExtra(EXTRA_PACKAGE) ?: BlockRules.targetPackage)
            put(BlockRecordStore.COL_HOST, host)
            put(BlockRecordStore.COL_SOURCE, intent.getStringExtra(EXTRA_SOURCE) ?: "unknown")
            put(BlockRecordStore.COL_RESULT, intent.getStringExtra(EXTRA_RESULT) ?: BlockRules.zeroAddress)
        }
        BlockRecordStore.insert(context, values)
        context.contentResolver.notifyChange(BlockRecordProvider.CONTENT_URI, null)
    }

    companion object {
        const val ACTION_RECORD_BLOCK = "com.fxxkad.dailyracing.action.RECORD_BLOCK"
        const val EXTRA_TIME = "time"
        const val EXTRA_PACKAGE = "package_name"
        const val EXTRA_HOST = "host"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_RESULT = "result"
    }
}
