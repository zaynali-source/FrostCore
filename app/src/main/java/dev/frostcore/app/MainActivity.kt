package dev.frostcore.app

import android.app.Activity
import android.os.Bundle
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppEntry(val label: String, val pkg: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FrostScreen(this) }
    }
}

private fun root(command: String): String {
    return try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        val code = p.waitFor()
        if (code == 0) out.ifBlank { "OK" } else "Root command failed ($code): $err $out"
    } catch (e: Exception) { "Root unavailable: ${e.message}" }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FrostScreen(activity: Activity) {
    var apps by remember { mutableStateOf(listOf<AppEntry>()) }
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Checking root/module…") }
    var mode by remember { mutableStateOf("manual") }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = activity.packageManager
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { it.packageName != activity.packageName }
                .map { AppEntry(pm.getApplicationLabel(it).toString(), it.packageName) }
                .sortedBy { it.label.lowercase() }
        }
        status = withContext(Dispatchers.IO) { root("cat /data/adb/frostcore/status.txt 2>/dev/null; echo; test -x /data/adb/frostcore/frostcore.sh && echo MODULE_OK || echo MODULE_NOT_FOUND") }
    }

    MaterialTheme(colorScheme = lightColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF386A8F), secondary = androidx.compose.ui.graphics.Color(0xFF526477))) {
        Scaffold(topBar = { TopAppBar(title = { Column { Text("FrostCore"); Text("Root app freezer · Alpha", style = MaterialTheme.typography.labelSmall) } }) }) { pad ->
            Column(Modifier.fillMaxSize().padding(pad).padding(16.dp)) {
                Text("Controller", style = MaterialTheme.typography.titleMedium)
                Text(status, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = mode == "manual", onClick = {
                        mode = "manual"; busy = true
                    }, label = { Text("Manual") })
                    FilterChip(selected = mode == "auto", onClick = {
                        mode = "auto"; busy = true
                    }, label = { Text("Auto · screen off") })
                    Button(onClick = {
                        busy = true
                    }) { Text("Apply") }
                }
                LaunchedEffect(busy, mode) {
                    if (busy) {
                        status = withContext(Dispatchers.IO) { root("mkdir -p /data/adb/frostcore; echo $mode > /data/adb/frostcore/mode; /data/adb/frostcore/frostcore.sh reload 2>&1") }
                        busy = false
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Search installed apps") }, singleLine = true)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Installed apps", style = MaterialTheme.typography.titleMedium)
                    Text("${apps.size} found", style = MaterialTheme.typography.bodySmall)
                }
                LazyColumn(Modifier.weight(1f)) {
                    items(apps.filter { it.label.contains(query, true) || it.pkg.contains(query, true) }, key = { it.pkg }) { app ->
                        var selected by remember { mutableStateOf(false) }
                        Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(app.label, style = MaterialTheme.typography.bodyLarge)
                                Text(app.pkg, style = MaterialTheme.typography.labelSmall)
                            }
                            Column {
                                Row {
                                    TextButton(onClick = {
                                        val safe = app.pkg.replace(Regex("[^A-Za-z0-9._]"), "")
                                        Thread {
                                            val result = root("/data/adb/frostcore/frostcore.sh freeze '$safe'")
                                            activity.runOnUiThread { status = result }
                                        }.start()
                                    }) { Text("Freeze") }
                                    TextButton(onClick = {
                                        Thread {
                                            val result = root("/data/adb/frostcore/frostcore.sh thaw-pkg '${app.pkg}'")
                                            activity.runOnUiThread { status = result }
                                        }.start()
                                    }) { Text("Thaw") }
                                    TextButton(onClick = {
                                        val safe = app.pkg.replace(Regex("[^A-Za-z0-9._]"), "")
                                        Thread {
                                            val result = root("mkdir -p /data/adb/frostcore; grep -qxF '$safe' /data/adb/frostcore/packages 2>/dev/null || echo '$safe' >> /data/adb/frostcore/packages")
                                            activity.runOnUiThread { status = "Added to Auto list: $result" }
                                        }.start()
                                    }) { Text("Auto") }
                                }
                                Row {
                                    Text("Protect", style = MaterialTheme.typography.labelSmall)
                                    Switch(checked = selected, onCheckedChange = {
                                        selected = it
                                        val safe = app.pkg.replace(Regex("[^A-Za-z0-9._]"), "")
                                        Thread {
                                            val cmd = if (it) "grep -qxF '$safe' /data/adb/frostcore/whitelist 2>/dev/null || echo '$safe' >> /data/adb/frostcore/whitelist" else "sed -i '/^$safe$/d' /data/adb/frostcore/whitelist"
                                            val result = root("mkdir -p /data/adb/frostcore; $cmd")
                                            activity.runOnUiThread { status = result }
                                        }.start()
                                    })
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { status = "Emergency thaw…"; Thread { val r = root("/data/adb/frostcore/frostcore.sh thaw-all"); activity.runOnUiThread { status = r } }.start() }) { Text("Emergency Thaw") }
                    OutlinedButton(onClick = { Thread { val r = root("/data/adb/frostcore/frostcore.sh status"); activity.runOnUiThread { status = r } }.start() }) { Text("Refresh status") }
                }
                Text("Alpha warning: only manage non-critical user apps. Switch currently edits whitelist; use the module config for exact freeze policy.", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
