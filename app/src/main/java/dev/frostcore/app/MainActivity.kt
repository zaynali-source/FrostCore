package dev.frostcore.app

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class AppEntry(val label: String, val pkg: String, val system: Boolean = false)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FrostScreen(this) }
    }
}

private fun root(command: String): String = try {
    val p = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
    val out = p.inputStream.bufferedReader().readText()
    val err = p.errorStream.bufferedReader().readText()
    val code = p.waitFor()
    if (code == 0) out.ifBlank { "OK" } else "Root command failed ($code): $err $out"
} catch (e: Exception) { "Root unavailable: ${e.message}" }

private fun safePkg(pkg: String) = pkg.matches(Regex("[A-Za-z0-9_.]+"))
private fun q(pkg: String) = "'$pkg'"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FrostScreen(activity: Activity) {
    val prefs = remember { activity.getSharedPreferences("frostcore-ui", 0) }
    var dark by remember { mutableStateOf(prefs.getBoolean("dark", false)) }
    var apps by remember { mutableStateOf(emptyList<AppEntry>()) }
    var configured by remember { mutableStateOf(emptySet<String>()) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Connecting to FrostCore…") }
    var mode by remember { mutableStateOf("manual") }
    var busy by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf("third") }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        if (uri != null) {
            try {
                activity.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { w ->
                    configured.sorted().forEach { w.appendLine(it) }
                }
                status = "Exported ${configured.size} apps"
            } catch (e: Exception) { status = "Export failed: ${e.message}" }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try {
                val entries = activity.contentResolver.openInputStream(uri)?.bufferedReader()?.readLines().orEmpty()
                    .map { it.trim() }.filter { safePkg(it) }.distinct()
                Thread {
                    entries.forEach { root("/data/adb/frostcore/frostcore.sh add ${q(it)}") }
                    val current = root("cat /data/adb/frostcore/packages 2>/dev/null")
                    activity.runOnUiThread {
                        configured = current.lines().filter { safePkg(it.trim()) }.toSet()
                        status = "Imported ${entries.size} valid package entries"
                    }
                }.start()
            } catch (e: Exception) { status = "Import failed: ${e.message}" }
        }
    }

    fun refresh() {
        Thread {
            val list = root("cat /data/adb/frostcore/packages 2>/dev/null")
            val stat = root("/data/adb/frostcore/frostcore.sh status 2>&1")
            val actualMode = root("cat /data/adb/frostcore/mode 2>/dev/null").trim()
            activity.runOnUiThread {
                configured = list.lines().map { it.trim() }.filter { safePkg(it) }.toSet()
                if (actualMode in setOf("manual", "auto", "deep")) mode = actualMode
                status = stat
            }
        }.start()
    }
    fun changePackages(packages: Set<String>, add: Boolean) {
        if (packages.isEmpty()) return
        Thread {
            packages.filter { safePkg(it) }.forEach {
                root("/data/adb/frostcore/frostcore.sh ${if (add) "add" else "remove"} ${q(it)}")
            }
            val list = root("cat /data/adb/frostcore/packages 2>/dev/null")
            activity.runOnUiThread {
                configured = list.lines().map { it.trim() }.filter { safePkg(it) }.toSet()
                selected = selected - packages
                status = "${if (add) "Added" else "Removed"} ${packages.size} apps"
            }
        }.start()
    }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = activity.packageManager
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { it.packageName != activity.packageName }
                .map { AppEntry(pm.getApplicationLabel(it).toString(), it.packageName,
                    (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0) }
                .sortedBy { it.label.lowercase() }
        }
        refresh()
    }

    val thirdParty = apps.filter { !it.system && (it.label.contains(query, true) || it.pkg.contains(query, true)) }
    val systemApps = apps.filter { it.system && (it.label.contains(query, true) || it.pkg.contains(query, true)) }
    val suggestedIds = setOf(
        "com.instagram.android", "com.facebook.katana", "com.facebook.orca",
        "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.snapchat.android",
        "com.twitter.android", "com.google.android.youtube", "com.reddit.frontpage",
        "com.linkedin.android", "com.pinterest", "com.spotify.music",
        "com.netflix.mediaclient", "com.amazon.mShop.android.shopping",
        "com.discord", "org.telegram.messenger"
    )
    val suggested = apps.filter { !it.system && it.pkg in suggestedIds && it.pkg !in configured &&
        (it.label.contains(query, true) || it.pkg.contains(query, true)) }

    fun forceStopSelected() {
        val targets = selected.filter { safePkg(it) && it != activity.packageName }.toSet()
        if (targets.isEmpty()) { status = "Select at least one app first"; return }
        Thread {
            var stopped = 0
            targets.forEach { pkg ->
                val result = root("/data/adb/frostcore/frostcore.sh force-stop '${pkg}'")
                if (!result.startsWith("Root command failed") && !result.startsWith("Root unavailable")) stopped++
            }
            activity.runOnUiThread {
                selected = selected - targets
                status = "Force-stopped $stopped of ${targets.size} selected apps"
            }
        }.start()
    }

    MaterialTheme(colorScheme = if (dark) darkColorScheme(
        primary = Color(0xFF9CCBFF), secondary = Color(0xFFB8C9DC),
        surface = Color(0xFF171D25), background = Color(0xFF0B1017),
        surfaceVariant = Color(0xFF222B36)
    ) else lightColorScheme(
        primary = Color(0xFF315F83), secondary = Color(0xFF526477),
        surface = Color(0xFFF8FAFC), background = Color(0xFFF1F5F9),
        surfaceVariant = Color(0xFFE7EDF4)
    )) {
        val colors = MaterialTheme.colorScheme
        Scaffold(containerColor = colors.background, topBar = {
            TopAppBar(
                title = { Column {
                    Text("FrostCore", style = MaterialTheme.typography.titleLarge)
                    Text("APP FREEZER  ·  VERSION 2.2", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                } },
                actions = {
                    IconButton(onClick = {
                        dark = !dark
                        prefs.edit().putBoolean("dark", dark).apply()
                    }) { Text(if (dark) "☀" else "☾", style = MaterialTheme.typography.titleLarge) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background)
            )
        }) { pad ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp))
                            .background(Brush.linearGradient(listOf(
                                colors.primary.copy(alpha = if (dark) .28f else .16f),
                                colors.surface.copy(alpha = .94f),
                                colors.secondary.copy(alpha = .10f)
                            )))
                            .border(1.dp, colors.outline.copy(alpha = .22f), RoundedCornerShape(26.dp))
                            .padding(18.dp).animateContentSize()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Your freezer", style = MaterialTheme.typography.titleLarge)
                                Text("${configured.size} apps in freeze list", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                            }
                            Surface(shape = RoundedCornerShape(50), color = colors.primary.copy(alpha = .15f)) {
                                Text("ROOT", Modifier.padding(horizontal = 12.dp, vertical = 7.dp), color = colors.primary, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(status, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 3)
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("Manual" to "manual", "Screen-off" to "auto", "Deep" to "deep").forEach { (label, value) ->
                                FilterChip(selected = mode == value, onClick = { mode = value }, label = { Text(label) })
                            }
                        }
                        Button(onClick = {
                            Thread {
                                val result = root("mkdir -p /data/adb/frostcore; echo $mode > /data/adb/frostcore/mode; /data/adb/frostcore/frostcore.sh reload 2>&1")
                                activity.runOnUiThread { status = result }
                            }.start()
                        }, modifier = Modifier.fillMaxWidth()) { Text("Apply mode") }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { exportLauncher.launch("FrostCore-app-list.txt") }, modifier = Modifier.weight(1f)) { Text("↑  Export list") }
                        OutlinedButton(onClick = { importLauncher.launch(arrayOf("text/plain", "text/*")) }, modifier = Modifier.weight(1f)) { Text("↓  Import list") }
                    }
                }
                item {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Search apps or package names") },
                        leadingIcon = { Text("⌕", style = MaterialTheme.typography.titleLarge) },
                        singleLine = true, shape = RoundedCornerShape(18.dp)
                    )
                }
                item {
                    AppSection(
                        title = "Suggested apps", subtitle = "Popular apps installed on your device",
                        apps = suggested, sectionKey = "suggested", expanded = expanded,
                        configured = configured, selected = selected,
                        onExpand = { expanded = if (expanded == "suggested") "" else "suggested" },
                        onSelect = { pkg, checked -> selected = if (checked) selected + pkg else selected - pkg },
                        onSelectAll = { all -> selected = if (all.all { it.pkg in selected }) selected - all.map { it.pkg }.toSet() else selected + all.map { it.pkg } },
                        onAdd = { all -> changePackages(all.map { it.pkg }.toSet(), true) },
                        onRemove = { all -> changePackages(all.map { it.pkg }.toSet(), false) }
                    )
                }
                item {
                    AppSection(
                        title = "Third-party apps", subtitle = "${thirdParty.size} user-installed apps",
                        apps = thirdParty, sectionKey = "third", expanded = expanded,
                        configured = configured, selected = selected,
                        onExpand = { expanded = if (expanded == "third") "" else "third" },
                        onSelect = { pkg, checked -> selected = if (checked) selected + pkg else selected - pkg },
                        onSelectAll = { all -> selected = if (all.all { it.pkg in selected }) selected - all.map { it.pkg }.toSet() else selected + all.map { it.pkg } },
                        onAdd = { all -> changePackages(all.map { it.pkg }.toSet(), true) },
                        onRemove = { all -> changePackages(all.map { it.pkg }.toSet(), false) }
                    )
                }
                item {
                    AppSection(
                        title = "System apps", subtitle = "${systemApps.size} system / preinstalled apps",
                        apps = systemApps, sectionKey = "system", expanded = expanded,
                        configured = configured, selected = selected,
                        onExpand = { expanded = if (expanded == "system") "" else "system" },
                        onSelect = { pkg, checked -> selected = if (checked) selected + pkg else selected - pkg },
                        onSelectAll = { all -> selected = if (all.all { it.pkg in selected }) selected - all.map { it.pkg }.toSet() else selected + all.map { it.pkg } },
                        onAdd = { all -> changePackages(all.map { it.pkg }.toSet(), true) },
                        onRemove = { all -> changePackages(all.map { it.pkg }.toSet(), false) }
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { changePackages(selected, true) }, modifier = Modifier.weight(1f), enabled = selected.isNotEmpty()) { Text("Add selected (${selected.size})") }
                        OutlinedButton(onClick = { changePackages(selected, false) }, modifier = Modifier.weight(1f), enabled = selected.isNotEmpty()) { Text("Remove selected") }
                    }
                    Button(
                        onClick = { forceStopSelected() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = selected.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) { Text("Force stop selected apps  ·  ${selected.size}") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                        Button(onClick = {
                            Thread { val result = root("/data/adb/frostcore/frostcore.sh thaw-all"); activity.runOnUiThread { status = result } }.start()
                        }, modifier = Modifier.weight(1f)) { Text("Emergency thaw") }
                        OutlinedButton(onClick = { refresh() }, modifier = Modifier.weight(1f)) { Text("Refresh") }
                    }
                }
                item {
                    Text(
                        "Force stop uses Android's package-level force-stop (not a raw PID kill), stopping all processes and background components for each selected package. System apps can be critical. Deep mode and force-stop may interrupt notifications, sync and alarms.",
                        style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AppSection(
    title: String, subtitle: String, apps: List<AppEntry>, sectionKey: String,
    expanded: String, configured: Set<String>, selected: Set<String>,
    onExpand: () -> Unit, onSelect: (String, Boolean) -> Unit,
    onSelectAll: (List<AppEntry>) -> Unit,
    onAdd: (List<AppEntry>) -> Unit, onRemove: (List<AppEntry>) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val isExpanded = expanded == sectionKey
    val allSelected = apps.isNotEmpty() && apps.all { it.pkg in selected }
    Surface(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(22.dp),
        color = colors.surface.copy(alpha = .88f),
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outline.copy(alpha = .16f))
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(subtitle, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
                TextButton(onClick = onExpand) { Text(if (isExpanded) "Hide" else "View") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = allSelected, onCheckedChange = { onSelectAll(apps) }, enabled = apps.isNotEmpty())
                Text("Select all", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                Text("${apps.count { it.pkg in configured }} added", style = MaterialTheme.typography.labelSmall, color = colors.primary)
            }
            AnimatedVisibility(visible = isExpanded, enter = fadeIn(), exit = fadeOut()) {
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = { onAdd(apps.filter { it.pkg in selected || allSelected }) }) { Text("Add selected") }
                        TextButton(onClick = { onRemove(apps.filter { it.pkg in selected || allSelected }) }) { Text("Remove selected") }
                    }
                    if (apps.isEmpty()) Text("No matching apps", color = colors.onSurfaceVariant, modifier = Modifier.padding(12.dp))
                    apps.forEach { app ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                                .background(if (app.pkg in selected) colors.primary.copy(alpha = .08f) else Color.Transparent)
                                .padding(horizontal = 4.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AppIcon(app)
                            Spacer(Modifier.width(10.dp))
                            Checkbox(checked = app.pkg in selected, onCheckedChange = { onSelect(app.pkg, it) })
                            Column(Modifier.weight(1f)) {
                                Text(app.label, style = MaterialTheme.typography.bodyMedium)
                                Text(app.pkg, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                            }
                            if (app.pkg in configured) Text("IN LIST", style = MaterialTheme.typography.labelSmall, color = colors.primary)
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun AppIcon(app: AppEntry) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val bitmap = remember(app.pkg) {
        runCatching {
            val drawable = context.packageManager.getApplicationIcon(app.pkg)
            val width = drawable.intrinsicWidth.coerceIn(1, 144)
            val height = drawable.intrinsicHeight.coerceIn(1, 144)
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { image ->
                val canvas = Canvas(image)
                drawable.setBounds(0, 0, width, height)
                drawable.draw(canvas)
            }.asImageBitmap()
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(bitmap = bitmap, contentDescription = app.label, modifier = Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)))
    } else {
        Surface(modifier = Modifier.size(42.dp), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Box(contentAlignment = Alignment.Center) { Text(app.label.take(1).uppercase()) }
        }
    }
}
