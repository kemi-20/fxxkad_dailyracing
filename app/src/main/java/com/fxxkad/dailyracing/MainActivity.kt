package com.fxxkad.dailyracing

import android.app.Activity
import android.content.Intent
import android.database.Cursor
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {
    private lateinit var totalView: TextView
    private lateinit var uniqueView: TextView
    private lateinit var rulesView: TextView
    private lateinit var statusView: TextView
    private lateinit var emptyView: TextView
    private lateinit var adapter: RecordAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        adapter = RecordAdapter()
        setContentView(buildContentView())
        refreshRecords()
    }

    override fun onResume() {
        super.onResume()
        refreshRecords()
    }

    private fun buildContentView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BG)
            setPadding(dp(16), dp(14), dp(16), dp(12))
        }

        root.addView(buildHeader())
        root.addView(buildStats())
        root.addView(buildActions())

        val listWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(Color.WHITE, 14f)
            setPadding(0, dp(6), 0, dp(6))
        }
        listWrap.addView(TextView(this).apply {
            text = "拦截日志"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_TEXT)
            setPadding(dp(14), dp(8), dp(14), dp(8))
        })

        emptyView = TextView(this).apply {
            text = "暂无拦截记录\n覆盖安装后请在 LSPosed 确认模块启用，并强停 com.romielf.mrsc 后重新打开"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(COLOR_MUTED)
            setPadding(dp(22), dp(34), dp(22), dp(34))
        }
        listWrap.addView(emptyView, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        listWrap.addView(ListView(this).apply {
            divider = null
            dividerHeight = 0
            cacheColorHint = Color.TRANSPARENT
            adapter = this@MainActivity.adapter
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(listWrap, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private var latestVersion: String? = null

    private fun buildHeader(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(COLOR_GREEN_DARK, 16f)
            setPadding(dp(16), dp(14), dp(16), dp(14))

            // Title row: title + ⋮ menu button
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL

                addView(TextView(this@MainActivity).apply {
                    text = "每日赛车 DNS 拦截"
                    textSize = 22f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

                addView(TextView(this@MainActivity).apply {
                    text = "⋮"
                    textSize = 24f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    setPadding(dp(12), dp(4), 0, dp(4))
                    setOnClickListener { showVersionMenu(it) }
                })
            })

            addView(TextView(this@MainActivity).apply {
                text = "目标：${BlockRules.targetPackage}  ·  命中域名返回 ${BlockRules.zeroAddress}"
                textSize = 13f
                setTextColor(Color.rgb(219, 245, 229))
                setPadding(0, dp(6), 0, 0)
                ellipsize = TextUtils.TruncateAt.END
                maxLines = 2
            })
        }
    }

    private fun showVersionMenu(anchor: View) {
        val popup = PopupMenu(this, anchor, Gravity.END)
        val currentVersion = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
        } catch (_: Exception) { "unknown" }
        popup.menu.add("当前版本：$currentVersion").apply { isEnabled = false }
        popup.menu.add("最新版本：${latestVersion ?: "检查中..."}").apply { isEnabled = false }
        popup.menu.add("GitHub 发布页").apply {
            setOnMenuItemClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(
                    "https://github.com/kemi-20/fxxkad_dailyracing/releases")))
                true
            }
        }
        popup.show()

        if (latestVersion == null) {
            fetchLatestVersion()
        }
    }

    private fun fetchLatestVersion() {
        Thread {
            try {
                val url = URL("https://api.github.com/repos/kemi-20/fxxkad_dailyracing/releases/latest")
                val conn = url.openConnection() as HttpURLConnection
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.connectTimeout = 8_000
                conn.readTimeout = 8_000
                val json = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val tag = JSONObject(json).getString("tag_name")
                latestVersion = tag
            } catch (_: Exception) {
                latestVersion = "获取失败"
            }
        }.start()
    }

    private fun buildStats(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, dp(6))
        }
        totalView = statCard(row, "总拦截", "0")
        uniqueView = statCard(row, "域名数", "0")
        rulesView = statCard(row, "规则", BlockRules.hosts.size.toString())
        return row
    }

    private fun statCard(parent: LinearLayout, label: String, value: String): TextView {
        val valueView: TextView
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(Color.WHITE, 14f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 12f
                setTextColor(COLOR_MUTED)
            })
            valueView = TextView(this@MainActivity).apply {
                text = value
                textSize = 24f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(COLOR_TEXT)
                setPadding(0, dp(2), 0, 0)
            }
            addView(valueView)
        }
        parent.addView(card, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = dp(8)
        })
        return valueView
    }

    private fun buildActions(): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(Color.WHITE, 14f)
            setPadding(dp(12), dp(10), dp(12), dp(12))
        }

        statusView = TextView(this).apply {
            textSize = 13f
            setTextColor(COLOR_MUTED)
        }
        wrap.addView(statusView)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        buttons.addView(Button(this).apply {
            text = "刷新"
            setOnClickListener { refreshRecords() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(Button(this).apply {
            text = "清空"
            setOnClickListener { clearRecords() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(10)
        })
        wrap.addView(buttons)
        return wrap
    }

    private fun refreshRecords() {
        val uri = BlockRecordProvider.CONTENT_URI.buildUpon()
            .appendQueryParameter("limit", "500")
            .build()
        val records = contentResolver.query(uri, null, null, null, null).useRecords()
        val displayRecords = records.deduplicateForDisplay()
        adapter.records = displayRecords
        adapter.notifyDataSetChanged()

        val uniqueHosts = records.map { it.host }.toSet().size
        totalView.text = records.size.toString()
        uniqueView.text = uniqueHosts.toString()
        rulesView.text = BlockRules.hosts.size.toString()
        emptyView.visibility = if (displayRecords.isEmpty()) View.VISIBLE else View.GONE
        statusView.text = "运行状态：等待目标应用触发 DNS 查询。列表已合并 1 分钟内重复域名。最近刷新：${formatTime(System.currentTimeMillis())}"
    }

    private fun List<BlockRecord>.deduplicateForDisplay(): List<BlockRecord> {
        val kept = mutableListOf<BlockRecord>()
        for (record in sortedBy { it.time }) {
            val hasRecentSameHost = kept.any {
                it.host == record.host && record.time - it.time in 0 until DISPLAY_DEDUP_WINDOW_MS
            }
            if (!hasRecentSameHost) {
                kept.add(record)
            }
        }
        return kept.sortedByDescending { it.time }
    }

    private fun clearRecords() {
        contentResolver.delete(BlockRecordProvider.CONTENT_URI, null, null)
        Toast.makeText(this, "拦截记录已清空", Toast.LENGTH_SHORT).show()
        refreshRecords()
    }

    private fun Cursor?.useRecords(): List<BlockRecord> {
        val cursor = this ?: return emptyList()
        return cursor.use {
            val idIndex = it.getColumnIndexOrThrow(BlockRecordProvider.COL_ID)
            val timeIndex = it.getColumnIndexOrThrow(BlockRecordProvider.COL_TIME)
            val hostIndex = it.getColumnIndexOrThrow(BlockRecordProvider.COL_HOST)
            val sourceIndex = it.getColumnIndexOrThrow(BlockRecordProvider.COL_SOURCE)
            val resultIndex = it.getColumnIndexOrThrow(BlockRecordProvider.COL_RESULT)
            buildList {
                while (it.moveToNext()) {
                    add(
                        BlockRecord(
                            id = it.getLong(idIndex),
                            time = it.getLong(timeIndex),
                            host = it.getString(hostIndex),
                            source = it.getString(sourceIndex),
                            result = it.getString(resultIndex)
                        )
                    )
                }
            }
        }
    }

    private fun formatTime(time: Long): String {
        return DateFormat.format("HH:mm:ss", time).toString()
    }

    private fun round(color: Int, radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius.toInt()).toFloat()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class BlockRecord(
        val id: Long,
        val time: Long,
        val host: String,
        val source: String,
        val result: String
    )

    private inner class RecordAdapter : BaseAdapter() {
        var records: List<BlockRecord> = emptyList()

        override fun getCount(): Int = records.size
        override fun getItem(position: Int): Any = records[position]
        override fun getItemId(position: Int): Long = records[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val holder: RecordHolder
            val view = if (convertView == null) {
                val container = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                    background = round(Color.WHITE, 0f)
                }
                holder = RecordHolder(
                    time = TextView(this@MainActivity).apply {
                        textSize = 12f
                        setTextColor(COLOR_MUTED)
                    },
                    host = TextView(this@MainActivity).apply {
                        textSize = 16f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(COLOR_TEXT)
                        setSingleLine(true)
                        ellipsize = TextUtils.TruncateAt.MIDDLE
                    },
                    detail = TextView(this@MainActivity).apply {
                        textSize = 12f
                        setTextColor(COLOR_MUTED)
                        setPadding(0, dp(4), 0, 0)
                        setSingleLine(true)
                        ellipsize = TextUtils.TruncateAt.END
                    }
                )
                container.addView(holder.time)
                container.addView(holder.host)
                container.addView(holder.detail)
                container.tag = holder
                container
            } else {
                holder = convertView.tag as RecordHolder
                convertView
            }

            val record = records[position]
            holder.time.text = formatTime(record.time)
            holder.host.text = record.host
            holder.detail.text = "已拦截 · ${record.result} · ${record.source}"
            return view
        }
    }

    private data class RecordHolder(
        val time: TextView,
        val host: TextView,
        val detail: TextView
    )

    companion object {
        private val COLOR_BG = Color.rgb(242, 246, 249)
        private val COLOR_TEXT = Color.rgb(23, 31, 42)
        private val COLOR_MUTED = Color.rgb(96, 112, 128)
        private val COLOR_GREEN_DARK = Color.rgb(22, 112, 76)
        private const val DISPLAY_DEDUP_WINDOW_MS = 60_000L
    }
}
