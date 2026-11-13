package com.blackout.cleaner

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings as SettingsIcon
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val crashFile = File(filesDir, "crash.txt")
        if (crashFile.exists()) {
            showCrash(crashFile)
            return
        }
        C.load(getSharedPreferences("cleaner", MODE_PRIVATE))
        Deep.init(applicationContext)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = C.bg, surface = C.surface, primary = C.teal)) {
                CleanerApp()
            }
        }
    }

    /** Plain Android views, no Compose, so the report still shows if Compose itself is what crashed. */
    private fun showCrash(file: File) {
        val text = runCatching { file.readText() }.getOrDefault("(unreadable)")
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(0xFF0B0D12.toInt())
        }
        fun label(t: String, size: Float, color: Int) = android.widget.TextView(this).apply {
            this.text = t
            textSize = size
            setTextColor(color)
        }
        col.addView(label("Cleaner crashed last time", 22f, 0xFFE8EAF0.toInt()))
        col.addView(android.widget.Button(this).apply {
            this.text = "Copy report"
            setOnClickListener {
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", text))
            }
        })
        col.addView(android.widget.Button(this).apply {
            this.text = "Continue"
            setOnClickListener { file.delete(); recreate() }
        })
        col.addView(label(text, 10f, 0xFF9AA3B5.toInt()).apply { setTextIsSelectable(true) })
        setContentView(android.widget.ScrollView(this).apply { addView(col) })
    }
}

@Composable
private fun CrashScreen(text: String, onContinue: () -> Unit) {
    val clip = LocalClipboardManager.current
    Column(Modifier.fillMaxSize().background(C.bg).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Cleaner crashed last time", color = C.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("Tap Copy, then paste it to Claude.", color = C.muted, fontSize = 13.sp)
        BigButton("Copy report", C.amber, Color(0xFF2A1D00), true) { clip.setText(AnnotatedString(text)) }
        BigButton("Continue", C.teal, Color.White, true, onContinue)
        SelectionContainer { Text(text, color = C.muted, fontSize = 10.sp) }
    }
}

private enum class Tab(val label: String, val icon: ImageVector, val key: String) {
    Home("Home", Icons.Outlined.Home, "teal"),
    Duplicates("Duplicates", Icons.Outlined.ContentCopy, "amber"),
    Junk("Junk", Icons.Outlined.DeleteOutline, "blue"),
    Deep("Deep", Icons.Outlined.Bolt, "violet"),
    Settings("Settings", Icons.Outlined.SettingsIcon, "violet");

    val color: Color get() = C.get(key)
}

@Composable
fun CleanerApp() {
    val ctx = LocalContext.current
    val vm: CleanerVM = viewModel()
    var granted by remember { mutableStateOf(hasAccess(ctx)) }
    var tabIndex by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) granted = hasAccess(ctx) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val tabs = Tab.values()
    Column(Modifier.fillMaxSize().background(C.bg)) {
        Box(Modifier.weight(1f)) {
            if (!granted) {
                AccessScreen { granted = hasAccess(ctx) }
            } else when (tabs[tabIndex]) {
                Tab.Home -> HomeScreen(vm) { tabIndex = it }
                Tab.Duplicates -> DuplicatesScreen(vm)
                Tab.Junk -> JunkScreen(vm)
                Tab.Deep -> DeepScreen()
                Tab.Settings -> SettingsScreen(vm)
            }
        }
        Text(
            Version.tag, color = C.muted, fontSize = 10.sp,
            modifier = Modifier.fillMaxWidth().background(C.bar).padding(start = 12.dp, top = 4.dp)
                .clickable { tabIndex = tabs.indexOf(Tab.Settings) }
        )
        Row(
            Modifier.fillMaxWidth().background(C.bar).padding(top = 4.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            tabs.forEachIndexed { i, t ->
                val on = i == tabIndex
                val col = if (on) t.color else C.muted
                Column(
                    Modifier.clip(RoundedCornerShape(12.dp)).clickable { tabIndex = i }
                        .padding(horizontal = 12.dp, vertical = 6.dp).width(72.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(t.icon, contentDescription = t.label, tint = col, modifier = Modifier.size(24.dp))
                    Text(t.label, color = col, fontSize = 11.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}

@Composable
private fun AccessScreen(onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onChanged() }
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
        Text("Cleaner needs file access", color = C.text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text(
            "Cleaner scans your photos, videos and downloads on this phone to find duplicates and junk. " +
                "Nothing leaves your phone. The app has no internet permission.",
            color = C.muted, fontSize = 14.sp
        )
        Spacer(Modifier.height(24.dp))
        BigButton("Allow access", C.teal, Color.White, true) {
            if (Build.VERSION.SDK_INT >= 30) {
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                )
            } else {
                launcher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
            }
        }
    }
}

@Composable
private fun Header(title: String, sub: String) {
    Column(Modifier.padding(bottom = 16.dp)) {
        Text(title, color = C.text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(sub, color = C.muted, fontSize = 13.sp)
    }
}

@Composable
private fun Panel(border: Color? = null, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    var m = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.surface)
    if (border != null) m = m.border(BorderStroke(1.dp, border), RoundedCornerShape(16.dp))
    if (onClick != null) m = m.clickable(onClick = onClick)
    Column(m.padding(14.dp), content = content)
}

@Composable
private fun BigButton(text: String, bg: Color, fg: Color, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = bg, contentColor = C.on(bg),
            disabledContainerColor = C.surface, disabledContentColor = C.muted
        ),
        modifier = Modifier.fillMaxWidth().height(54.dp)
    ) { Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(10.dp))
            .background(if (selected) C.violet else C.bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(label, color = if (selected) C.on(C.violet) else C.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun CardRow(color: Color, icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
    Panel(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) { Icon(icon, contentDescription = null, tint = color) }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(sub, color = C.muted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun HomeScreen(vm: CleanerVM, go: (Int) -> Unit) {
    val (total, free) = vm.storage()
    val used = total - free
    val frac = if (total > 0) (used.toFloat() / total).coerceIn(0f, 1f) else 0f
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Header("Cleaner", "Everything stays on your phone")
        Panel {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Storage", color = C.muted, fontSize = 13.sp)
                Text("${fmtSize(used)} of ${fmtSize(total)} used", color = C.muted, fontSize = 13.sp)
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(6.dp)).background(C.bg)) {
                Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(C.teal))
            }
        }
        Spacer(Modifier.height(16.dp))
        BigButton(if (vm.scanning) "Scanning..." else "Scan now", C.teal, Color.White, !vm.scanning) { vm.scan() }
        if (vm.status.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(vm.status, color = C.muted, fontSize = 13.sp)
        }
        Spacer(Modifier.height(16.dp))
        CardRow(
            C.amber, Icons.Outlined.ContentCopy, "Duplicates",
            if (vm.scanned) "${vm.dupCount()} extra copies - ${fmtSize(vm.dupBytes())}" else "Run a scan"
        ) { go(1) }
        Spacer(Modifier.height(10.dp))
        CardRow(
            C.blue, Icons.Outlined.DeleteOutline, "Junk and large files",
            if (vm.scanned) "${fmtSize(vm.junkBytes())} selected to clear" else "Run a scan"
        ) { go(2) }
        Spacer(Modifier.height(10.dp))
        CardRow(C.violet, Icons.Outlined.Bolt, "Deep Clean", "Clear every app's cache with Shizuku") { go(3) }
    }
}

@Composable
private fun DupRow(item: Item, removed: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(C.line),
            contentAlignment = Alignment.Center
        ) { Thumb(item.file) { Icon(Icons.Outlined.ContentCopy, contentDescription = null, tint = C.muted) } }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.file.name, color = C.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${shortPath(item.file)} - ${fmtDate(item.date)} - ${fmtSize(item.size)}", color = C.muted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Text(if (removed) "REMOVE" else "KEEP", color = if (removed) C.red else C.ink(C.teal), fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DuplicatesScreen(vm: CleanerVM) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(top = 20.dp, bottom = 12.dp)) {
        Header("Duplicates", "Matched by content, ${if (vm.keepOldest) "oldest" else "newest"} kept. Tap a file to flip it.")
        if (vm.groups.isEmpty()) {
            Text(if (vm.scanned) "No duplicates found." else "Run a scan from Home.", color = C.muted)
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(vm.groups) { g ->
                Panel {
                    Text("${g.items.size} identical copies", color = C.amber, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    g.items.forEach { it0 -> DupRow(it0, it0.path in vm.removeSet) { vm.toggleDup(g, it0) } }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        BigButton(
            "Move ${vm.dupCount()} to Trash - ${fmtSize(vm.dupBytes())}", C.amber, Color(0xFF2A1D00), vm.dupCount() > 0
        ) { vm.cleanDuplicates() }
    }
}

@Composable
private fun JunkScreen(vm: CleanerVM) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(top = 20.dp, bottom = 12.dp)) {
        Header("Junk and large files", "Review before anything is removed")
        if (vm.junk.isEmpty()) {
            Text(if (vm.scanned) "Nothing to clean." else "Run a scan from Home.", color = C.muted)
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(vm.junk) { cat ->
                Panel {
                    val bytes = cat.items.sumOf { it.size }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!cat.perItem) {
                            Checkbox(
                                checked = cat.items.all { it.path in vm.junkSet },
                                onCheckedChange = { vm.toggleJunkCat(cat) },
                                colors = CheckboxDefaults.colors(checkedColor = C.blue, uncheckedColor = C.muted, checkmarkColor = C.bg)
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(cat.title, color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text(cat.note, color = C.muted, fontSize = 12.sp)
                        }
                        Text(fmtSize(bytes), color = C.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                    if (cat.perItem) {
                        cat.items.forEach { it0 ->
                            Row(
                                Modifier.fillMaxWidth().clickable { vm.toggleJunkItem(it0) },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = it0.path in vm.junkSet,
                                    onCheckedChange = { vm.toggleJunkItem(it0) },
                                    colors = CheckboxDefaults.colors(checkedColor = C.blue, uncheckedColor = C.muted, checkmarkColor = C.bg)
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(it0.file.name, color = C.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${shortPath(it0.file)} - ${fmtDate(it0.date)}", color = C.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Text(fmtSize(it0.size), color = C.muted, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        BigButton(
            "Clean selected - ${fmtSize(vm.junkBytes())}", C.blue, Color(0xFF04182E), vm.junkCount() > 0
        ) { vm.cleanJunk() }
    }
}

@Composable
private fun SettingsScreen(vm: CleanerVM) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Header("Settings", "Offline - no internet permission")
        Panel {
            Text("Keep when duplicate", color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text("Which copy stays when files are identical", color = C.muted, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Oldest", vm.keepOldest) { vm.updateKeepOldest(true) }
                Chip("Newest", !vm.keepOldest) { vm.updateKeepOldest(false) }
            }
        }
        Panel {
            Text("Trash length", color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text("Deleted items stay recoverable this long", color = C.muted, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(7, 14, 30).forEach { d -> Chip("$d days", vm.trashDays == d) { vm.updateTrashDays(d) } }
            }
            Spacer(Modifier.height(12.dp))
            Text("In trash: ${vm.trashInfo.first} files - ${fmtSize(vm.trashInfo.second)}", color = C.muted, fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Restore all", false) { vm.restoreTrash() }
                Chip("Empty trash", false) { vm.emptyTrash() }
            }
        }
        Panel {
            Text("Colors", color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text("Tap a color to change it with the color wheel", color = C.muted, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
            val cprefs = LocalContext.current.getSharedPreferences("cleaner", android.content.Context.MODE_PRIVATE)
            var editing by remember { mutableStateOf<String?>(null) }
            listOf("teal" to "Home and scan", "amber" to "Duplicates", "blue" to "Junk", "violet" to "Settings and Deep Clean").forEach { (k, label) ->
                Row(
                    Modifier.fillMaxWidth().clickable { editing = k }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(28.dp).clip(RoundedCornerShape(14.dp)).background(C.get(k)).border(2.dp, C.line, RoundedCornerShape(14.dp)))
                    Spacer(Modifier.width(12.dp))
                    Text(label, color = C.text, fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(6.dp))
            Chip("Reset colors", false) { C.reset(cprefs) }
            editing?.let { k ->
                ColorPickerDialog("Pick a color", C.get(k), { c -> C.set(k, c, cprefs); editing = null }, { editing = null })
            }
        }
        Panel(border = C.violet.copy(alpha = 0.35f)) {
            Text("Deep Clean", color = C.violet, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Uses the Shizuku app (open source, not Google). Open the Deep tab to set it up.",
                color = C.muted, fontSize = 12.sp
            )
        }
        if (vm.status.isNotEmpty()) Text(vm.status, color = C.muted, fontSize = 13.sp)
        val relCtx = androidx.compose.ui.platform.LocalContext.current
        Chip("Open GitHub Releases", false) { Version.openReleases(relCtx) }
        Text(Version.full, color = C.muted, fontSize = 12.sp)
    }
}

@Composable
private fun DeepScreen() {
    val ctx = LocalContext.current
    DisposableEffect(Unit) { Deep.refresh(ctx); onDispose { } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Header("Deep Clean", "Clears the cache of every app. Nothing else is touched.")
        val (title, steps) = when (Deep.state) {
            "ready" -> "Ready" to ""
            "needs_permission" -> "Shizuku is running" to "Tap Allow so Cleaner may use it."
            "denied" -> "Permission was refused" to "Open Shizuku > Authorized apps and switch Cleaner on."
            "stopped" -> "Shizuku is installed but not running" to
                "1. Settings > Developer options > Wireless debugging: turn on (Wi-Fi must be connected).\n" +
                "2. Open Shizuku and tap Pairing, then enter the pairing code from the notification.\n" +
                "3. In Shizuku tap Start.\n" +
                "4. Come back here."
            else -> "Shizuku is not installed" to
                "1. On your PC or phone browser, download the Shizuku APK from shizuku.rikka.app/download (the GitHub release, not the Play Store).\n" +
                "2. Install it.\n" +
                "3. Come back here."
        }
        Panel(border = C.violet.copy(alpha = 0.35f)) {
            Text(title, color = C.violet, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (steps.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(steps, color = C.muted, fontSize = 13.sp)
            }
            Spacer(Modifier.height(12.dp))
            when (Deep.state) {
                "ready" -> BigButton(if (Deep.busy) "Working..." else "Run Deep Clean", C.violet, Color.Black, !Deep.busy) { Deep.run(ctx) }
                "needs_permission" -> BigButton("Allow", C.violet, Color.Black, true) { Deep.requestPermission() }
                "stopped", "denied" -> BigButton("Open Shizuku", C.violet, Color.Black, Deep.installed(ctx)) { Deep.openShizuku(ctx) }
                else -> {}
            }
        }
        if (Deep.result.isNotEmpty()) Text(Deep.result, color = C.text, fontSize = 14.sp)
        Text("Cleaner never connects to the internet. It talks to Shizuku on this phone only.", color = C.muted, fontSize = 12.sp)
    }
}
