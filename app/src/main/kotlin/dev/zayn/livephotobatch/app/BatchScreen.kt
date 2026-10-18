package dev.zayn.livephotobatch.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.zayn.livephotobatch.app.browse.DirBrowser
import dev.zayn.livephotobatch.core.FailureReason
import dev.zayn.livephotobatch.core.ItemResult
import dev.zayn.livephotobatch.core.ItemStatus
import java.io.File

/** 目录浏览弹窗现在在给哪个字段选路径；null 表示没打开。 */
private enum class Picking { Source, Target }

/**
 * 一屏装下全部功能：权限、两个目录、危险开关、进度与报告。
 *
 * 三个刻意的设计点：
 * 1. **报告列表不用 `LazyColumn`。** 外层已经是一条 `verticalScroll` 的 `Column`，
 *    在里面再嵌一个纵向 `LazyColumn` 会让它带着无限高度去测量，Compose 直接抛
 *    `"Vertically scrollable component was measured with an infinity maximum height constraints"`。
 *    报告最多几百行，普通 `Column` + `forEach` 足够，也不需要滚动嵌套。
 * 2. **权限不在组合过程里读。** 授权状态由 [BatchViewModel] 构造时、`MainActivity.onResume`
 *    和这里的 `LaunchedEffect` 推进；组合期每次重组都调一次
 *    `Environment.isExternalStorageManager()`（一次 binder 调用）会在批次逐项回报时拖慢主线程。
 * 3. **文本框只读。** 路径只能来自 [DirectoryPicker]，界面上显示的绝对路径就是批次真正会用的串
 *    （`DirBrowser` 给的都是真实路径，见 [BatchViewModel.isSelectable] 那条 root 之外的分叉）。
 */
@Composable
fun BatchScreen(vm: BatchViewModel, request: () -> Unit) {
    val state by vm.state.collectAsState()
    var picking by remember { mutableStateOf<Picking?>(null) }

    LaunchedEffect(Unit) { vm.refreshPermission() }

    Column(
        // targetSdk 35+ 起窗口强制 edge-to-edge，不躲开状态栏/导航栏的话第一行权限提示会被状态栏压住。
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        if (!state.permissionGranted) {
            Text(
                "还没有“所有文件访问”权限，读写公共目录会被系统拒绝，批次不会开始。",
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = request) { Text("授予所有文件访问权限") }
            Spacer(Modifier.height(12.dp))
        }

        PathRow(
            label = "源目录",
            path = state.sourceDir,
            enabled = !state.running,
            onBrowse = { picking = Picking.Source },
        )
        PathRow(
            label = "目标目录",
            path = state.targetDir,
            enabled = !state.running,
            onBrowse = { picking = Picking.Target },
        )

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = state.deleteOriginals,
                onCheckedChange = vm::setDeleteOriginals,
                enabled = !state.running,
            )
            Text(
                "合成后删除原文件（不可撤销）",
                // 危险项就要看着危险：勾上之后整行换成 error 色，再加一句提醒。
                color = if (state.deleteOriginals) MaterialTheme.colorScheme.error else Color.Unspecified,
                modifier = Modifier.clickable(enabled = !state.running) {
                    vm.setDeleteOriginals(!state.deleteOriginals)
                },
            )
        }
        if (state.deleteOriginals) {
            Text(
                "只有改名成功的项目才会删源文件；删不掉的会在报告里标成警告，产物不会被回退成失败。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // 没授权就点不动：spec §9 把权限列为全局前置条件，别让人先按下开始再收一条错误。
            Button(onClick = vm::start, enabled = state.permissionGranted && !state.running) {
                Text("开始合成")
            }
            Button(onClick = vm::cancel, enabled = state.running) { Text("取消") }
        }

        state.globalError?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(12.dp))
        Text("进度 ${state.done}/${state.total}，已写出 ${state.bytes / 1024 / 1024} MB")
        LinearProgressIndicator(
            progress = { if (state.total == 0) 0f else state.done.toFloat() / state.total },
            modifier = Modifier.fillMaxWidth(),
        )

        if (state.items.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("报告", style = MaterialTheme.typography.titleSmall)
            // spec §9：单项失败不中断批次，但 UI 要在末尾汇总计数（磁盘写满就是逐项 IO_ERROR）。
            val counts = state.items.groupingBy { it.status }.eachCount()
            val warned = state.items.count { it.deleteFailures.isNotEmpty() }
            Text(
                buildString {
                    append("成功 ${counts[ItemStatus.SUCCESS] ?: 0}")
                    append(" · 跳过 ${counts[ItemStatus.SKIPPED] ?: 0}")
                    append(" · 失败 ${counts[ItemStatus.FAILED] ?: 0}")
                    if (warned > 0) append(" · 原文件没删掉 $warned")
                },
                style = MaterialTheme.typography.bodySmall,
            )
            state.items.forEach { item ->
                Text(itemLine(item), Modifier.padding(vertical = 2.dp))
            }
        }
    }

    picking?.let { which ->
        DirectoryPicker(
            browser = vm.browser,
            root = vm.root,
            selectable = vm::isSelectable,
            startPath = if (which == Picking.Source) state.sourceDir else state.targetDir,
            onDismiss = { picking = null },
            onPick = { path ->
                when (which) {
                    Picking.Source -> vm.setSource(path)
                    Picking.Target -> vm.setTarget(path)
                }
                picking = null
            },
        )
    }
}

@Composable
private fun PathRow(label: String, path: String, enabled: Boolean, onBrowse: () -> Unit) {
    OutlinedTextField(
        value = path,
        onValueChange = { },
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        enabled = false,
        singleLine = true,
    )
    TextButton(onClick = onBrowse, enabled = enabled) { Text("浏览…") }
}

/**
 * 目录浏览器。起点是 [startPath]（不可用时退回 [root]），一级一级往下走。
 *
 * [root] 之外（比如从根再点"上一级"到 `/storage/emulated`）照样能浏览——`DirBrowser.subdirs`
 * 只看 `startsWith("/")`，本来就是个宽口子；但 [selectable] 为假时会禁掉"选这里"并说明原因，
 * 因为那种串喂给 `FileStorage.resolve()` 会被重新拼到 root 之下，选了就等于把批次指向另一个目录。
 *
 * 列目录是磁盘调用，所以 `presets()` / `subdirs()` 都 `remember` 在路径上：批次逐项回报会频繁
 * 重组这一屏，不能让每帧都去 `listFiles()`。
 */
@Composable
private fun DirectoryPicker(
    browser: DirBrowser,
    root: String,
    selectable: (String) -> Boolean,
    startPath: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var path by remember(root, startPath) { mutableStateOf(if (selectable(startPath)) startPath else root) }
    val presets = remember(browser) { browser.presets() }
    val subdirs = remember(path) { browser.subdirs(path) }
    // 上一级只能自己算：DirItem.parent 的定义是"这批条目出自哪个目录"（subdirs 里就是被列的
    // 目录本身，presets 里才是该项的父级），当不了从 path 往上走的串。
    // 这里用 java.io.File 的 parent 语义——和 DirBrowser 内部拼路径用的是同一套 API，不手写 "/"。
    val parent = remember(path) { File(path).parentFile?.absolutePath ?: "" }
    val canPick = selectable(path)

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onPick(path) }, enabled = canPick) { Text("选这里") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        title = { Text(path) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                if (!canPick) {
                    Text(
                        "这个目录在存储根 $root 之外，只能看，不能当源/目标。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (parent.isNotEmpty()) {
                    TextButton(onClick = { path = parent }) { Text("⬆ 上一级", maxLines = 1) }
                }
                presets.forEach { p ->
                    TextButton(onClick = { path = p.absolutePath }) { Text(p.name, maxLines = 1) }
                }
                if (subdirs.isEmpty()) {
                    Text("（没有可进入的子目录）", style = MaterialTheme.typography.bodySmall)
                }
                subdirs.forEach { d ->
                    TextButton(onClick = { path = d.absolutePath }) {
                        Text(d.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
    )
}

/** 一行报告文案。`when (item.status)` 的三个分支是穷尽的，核心层加状态时这里会编译不过。 */
private fun itemLine(item: ItemResult): String {
    val head = when (item.status) {
        ItemStatus.SUCCESS -> "成功 ${item.stem} → ${item.outputName}"
        ItemStatus.SKIPPED -> "跳过 ${item.stem}：${reasonText(item.reason)}"
        ItemStatus.FAILED -> "失败 ${item.stem}：${reasonText(item.reason)}"
    }
    // deleteFailures 只会挂在 SUCCESS 上：Task 8 规定改名成功后状态不再被回改写，
    // 删不掉的源文件是警告。所以警告必须跟着 SUCCESS 一起显示，不能只在失败分支里拼。
    val warning = if (item.deleteFailures.isEmpty()) ""
    else "（原文件删除失败：${item.deleteFailures.joinToString()}）"
    return head + warning
}

/** `FailureReason` 的中文说法；spec §9 要求面向用户给中文短语，同时保留机器可读的枚举名。 */
private fun reasonText(reason: FailureReason?): String {
    val zh = when (reason) {
        null -> "未说明"
        FailureReason.NOT_JPEG -> "不是 JPEG"
        FailureReason.MALFORMED_JPEG -> "JPEG 结构损坏"
        FailureReason.ALREADY_MOTION_PHOTO -> "已是动态照片"
        FailureReason.EXTENDED_XMP_UNSUPPORTED -> "扩展 XMP 不支持"
        FailureReason.NOT_MP4 -> "不是 MP4"
        FailureReason.IO_ERROR -> "读写故障"
        FailureReason.NO_PAIR -> "没有配对的文件"
    }
    return if (reason == null) zh else "$zh（${reason.name}）"
}
