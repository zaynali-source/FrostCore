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

    fun runRoot(command: String) {
        Thread {
            val result = root(command)
            activity.runOnUiThread { status = result }
        }.start()
    }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = activity.packageManager
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { it.packageName != activity.packageName }
                .map { AppEntry(pm.getApplicationLabel(it).toString(), it.packageName) }
                .sortedBy { it.label.lowercase() }
        }
        status = withContext(Dispatchers.IO) {
            root("cat /data/adb/frostcore/status.txt 2>/dev/null; /data/adb/frostcore/frostcore.sh status 2>&1; test -x /data/adb/frostcore/frostcore.sh && echo MODULE_OK || echo MODULE_NOT_FOUND")
        }
    }

    MaterialTheme(colorScheme = lightColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF386A8F), secondary = androidx.compose.ui.graphics.Color(0xFF526477))) {
        Scaffold(topBar = {
            TopAppBar(title = {
                Column {
                    Text("FrostCore")
                    Text("Root App Manager · v2.0", style = MaterialTheme.typography.labelSmall)
                }
            })
        }) { pad ->
            Column(Modifier.fillMaxSize().padding(pad).padding(16.dp)) {
                Text("Controller", style = MaterialTheme.typography.titleMedium)
                Text(status, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = mode == "manual", onClick = { mode = "manual"; busy = true }, label = { Text("Manual") })
                    FilterChip(selected = mode == "auto", onClick = { mode = "auto"; busy = true }, label = { Text("Screen-off") })
                    FilterChip(selected = mode == "deep", onClick = { mode = "deep"; busy = true }, label = { Text("Deep Freeze") })
                }
                Button(onClick = { busy = true }, enabled = !busy) { Text(if (busy) "Applying…" else "Apply mode") }
                LaunchedEffect(busy, mode) {
                    if (busy) {
                        status = withContext(Dispatchers.IO) {
                            root("mkdir -p /data/adb/frostcore; echo $mode > /data/adb/frostcore/mode; /data/adb/frostcore/frostcore.sh reload 2>&1")
                        }
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
                        var protected by remember { mutableStateOf(false) }
                        val safe = app.pkg.replace(Regex("[^A-Za-z0-9._]"), "")
                        Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(app.label, style = MaterialTheme.typography.bodyLarge)
                                Text(app.pkg, style = MaterialTheme.typography.labelSmall)
                                Row {
                                    TextButton(onClick = { runRoot("/data/adb/frostcore/frostcore.sh freeze '$safe'") }) { Text("Freeze") }
                                    TextButton(onClick = { runRoot("/data/adb/frostcore/frostcore.sh thaw-pkg '$safe'") }) { Text("Thaw") }
                                }
                                Row {
                                    TextButton(onClick = {
                                        runRoot("mkdir -p /data/adb/frostcore; /data/adb/frostcore/frostcore.sh add '$safe'")
                                    }) { Text("Add to list") }
                                    TextButton(onClick = {
                                        runRoot("mkdir -p /data/adb/frostcore; /data/adb/frostcore/frostcore.sh add '$safe'; echo deep > /data/adb/frostcore/mode; /data/adb/frostcore/frostcore.sh reload")
                                        mode = "deep"
                                    }) { Text("Deep Freeze") }
                                }
                                Row {
                                    Text("Protect", style = MaterialTheme.typography.labelSmall)
                                    Switch(checked = protected, onCheckedChange = {
                                        protected = it
                                        val cmd = if (it) "grep -qxF '$safe' /data/adb/frostcore/whitelist 2>/dev/null || echo '$safe' >> /data/adb/frostcore/whitelist" else "grep -vxF '$safe' /data/adb/frostcore/whitelist > /data/adb/frostcore/whitelist.tmp 2>/dev/null; mv /data/adb/frostcore/whitelist.tmp /data/adb/frostcore/whitelist"
                                        runRoot("mkdir -p /data/adb/frostcore; $cmd")
                                    })
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { runRoot("/data/adb/frostcore/frostcore.sh thaw-all") }) { Text("Emergency Thaw") }
                    OutlinedButton(onClick = { runRoot("/data/adb/frostcore/frostcore.sh status") }) { Text("Refresh") }
                }
                Text(
                    "Deep Freeze force-stops selected apps after they leave foreground for 8 seconds. Android relaunches them when you tap their icon. Notifications, background sync and alarms may be interrupted. Protect critical apps.",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}
