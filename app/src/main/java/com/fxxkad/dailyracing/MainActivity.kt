package com.fxxkad.dailyracing

import android.app.Application
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class BlockRecord(
    val id: Long,
    val time: Long,
    val host: String,
    val source: String,
    val result: String
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val _records = MutableStateFlow<List<BlockRecord>>(emptyList())
    val records = _records.asStateFlow()

    private val _totalCount = MutableStateFlow<Long>(0L)
    val totalCount = _totalCount.asStateFlow()

    private val _ruleCount = MutableStateFlow(0)
    val ruleCount = _ruleCount.asStateFlow()

    private val _fixShareEnabled = MutableStateFlow<Boolean>(true)
    val fixShareEnabled = _fixShareEnabled.asStateFlow()

    private val _latestVersion = MutableStateFlow<String?>(null)
    val latestVersion = _latestVersion.asStateFlow()

    private val DISPLAY_DEDUP_WINDOW_MS = 60_000L

    init {
        refreshRecords()
        fetchSettings()
    }

    private fun fetchSettings() {
        viewModelScope.launch(Dispatchers.IO) {
            val contentResolver = getApplication<Application>().contentResolver
            val uri = BlockRecordProvider.CONTENT_URI
            val bundle = contentResolver.call(uri, "get_setting", "fix_share", null)
            _fixShareEnabled.value = bundle?.getBoolean("value", true) ?: true
        }
    }

    fun toggleFixShare(enabled: Boolean) {
        _fixShareEnabled.value = enabled
        viewModelScope.launch(Dispatchers.IO) {
            val contentResolver = getApplication<Application>().contentResolver
            val uri = BlockRecordProvider.CONTENT_URI
            val extras = Bundle().apply { putBoolean("value", enabled) }
            contentResolver.call(uri, "set_setting", "fix_share", extras)
        }
    }

    fun refreshRecords() {
        viewModelScope.launch(Dispatchers.IO) {
            val contentResolver = getApplication<Application>().contentResolver
            val uri = BlockRecordProvider.CONTENT_URI.buildUpon()
                .appendQueryParameter("limit", "500")
                .build()

            // Query total count via call()
            val statsBundle = contentResolver.call(BlockRecordProvider.CONTENT_URI, "get_stats", null, null)
            val count = statsBundle?.getLong("total_count", 0L) ?: 0L
            val ruleCount = statsBundle?.getInt("rule_count", 0) ?: 0

            val cursor = contentResolver.query(uri, null, null, null, null)
            val newRecords = cursor.useRecords().deduplicateForDisplay()

            _totalCount.value = maxOf(count, newRecords.size.toLong())
            _ruleCount.value = ruleCount
            _records.value = newRecords
        }
    }

    fun clearRecords() {
        viewModelScope.launch(Dispatchers.IO) {
            getApplication<Application>().contentResolver.delete(BlockRecordProvider.CONTENT_URI, null, null)
            refreshRecords()
        }
    }

    fun fetchLatestVersion() {
        if (_latestVersion.value != null && _latestVersion.value != "获取失败" && _latestVersion.value != "检查中...") return

        _latestVersion.value = "检查中..."
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val url = URL("https://api.github.com/repos/kemi-20/fxxkad_dailyracing/releases/latest")
                val conn = url.openConnection() as HttpURLConnection
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.connectTimeout = 8_000
                conn.readTimeout = 8_000
                val json = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val tag = JSONObject(json).getString("tag_name")
                _latestVersion.value = tag
            } catch (_: Exception) {
                _latestVersion.value = "获取失败"
            }
        }
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
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    val records by viewModel.records.collectAsStateWithLifecycle()
    val ruleCount by viewModel.ruleCount.collectAsStateWithLifecycle()
    val latestVersion by viewModel.latestVersion.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("每日赛车 DNS 拦截", fontWeight = FontWeight.Bold) },
                actions = {
                    VersionMenu(latestVersion, viewModel::fetchLatestVersion)
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    text = "目标：${BlockRules.targetPackage}\n命中域名返回 ${BlockRules.zeroAddress}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard("总拦截", viewModel.totalCount.collectAsStateWithLifecycle().value.toString(), Modifier.weight(1f))
                    StatCard("域名数", records.distinctBy { it.host }.size.toString(), Modifier.weight(1f))
                    StatCard("规则", ruleCount.toString(), Modifier.weight(1f))
                }
            }

            item {
                ActionsCard(
                    onRefresh = viewModel::refreshRecords,
                    onClear = viewModel::clearRecords,
                    fixShareEnabled = viewModel.fixShareEnabled.collectAsStateWithLifecycle().value,
                    onToggleFixShare = viewModel::toggleFixShare
                )
            }

            item {
                Text(
                    text = "拦截日志",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                )
            }

            if (records.isEmpty()) {
                item {
                    Text(
                        text = "暂无拦截记录\n覆盖安装后请在 LSPosed 确认模块启用，并强停 ${BlockRules.targetPackage} 后重新打开",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp, horizontal = 16.dp)
                    )
                }
            } else {
                items(records, key = { it.id }) { record ->
                    RecordItem(record)
                }
            }
        }
    }
}

@Composable
fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    ElevatedCard(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        ),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun ActionsCard(
    onRefresh: () -> Unit,
    onClear: () -> Unit,
    fixShareEnabled: Boolean,
    onToggleFixShare: (Boolean) -> Unit
) {
    val context = LocalContext.current
    ElevatedCard(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("修复分享", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "拦截由于非官方重打包导致的富文本卡片分享失败，将其强制转换为纯文本链接分享（支持 QQ 和微信）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Switch(checked = fixShareEnabled, onCheckedChange = onToggleFixShare)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            val time = remember { mutableStateOf(System.currentTimeMillis()) }
            Text(
                text = "运行状态：等待目标应用触发 DNS 查询。列表已合并 1 分钟内重复域名。最近刷新：${DateFormat.format("HH:mm:ss", time.value)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(
                    onClick = {
                        onRefresh()
                        time.value = System.currentTimeMillis()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("刷新")
                }
                Button(
                    onClick = {
                        onClear()
                        Toast.makeText(context, "拦截记录已清空", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("清空")
                }
            }
        }
    }
}

@Composable
fun RecordItem(record: BlockRecord) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = record.host,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = DateFormat.format("HH:mm:ss", record.time).toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "已拦截 · ${record.result} · ${record.source}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun VersionMenu(latestVersion: String?, onFetchVersion: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val currentVersion = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    } catch (_: Exception) { "unknown" }

    Box {
        IconButton(onClick = {
            expanded = true
            onFetchVersion()
        }) {
            Icon(Icons.Default.MoreVert, contentDescription = "Menu")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("当前版本：$currentVersion") },
                onClick = { },
                enabled = false
            )
            DropdownMenuItem(
                text = { Text("最新版本：${latestVersion ?: "检查中..."}") },
                onClick = { },
                enabled = false
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("GitHub 发布页") },
                onClick = {
                    expanded = false
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(
                        "https://github.com/kemi-20/fxxkad_dailyracing/releases"
                    )))
                }
            )
        }
    }
}
