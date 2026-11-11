package com.blackout.cleaner

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.ExifInterface
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale

val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp")
val VIDEO_EXT = setOf("mp4", "mkv", "mov", "3gp", "webm", "avi", "m4v")
private val STANDARD_DIRS = setOf(
    "Download", "DCIM", "Pictures", "Movies", "Music", "Documents",
    "Podcasts", "Ringtones", "Notifications", "Alarms", "Audiobooks", "Recordings"
)
private val COPY_NAME = Regex(".*(\\(\\d+\\)| \\d+| copy|-\\d+)\\.[^.]+$", RegexOption.IGNORE_CASE)

data class Item(val file: File, val size: Long, val date: Long) {
    val path: String get() = file.absolutePath
}

data class DupGroup(val items: List<Item>)

data class JunkCat(
    val id: String,
    val title: String,
    val note: String,
    val items: List<Item>,
    val direct: Boolean,   // true = delete directly (caches, empty folders), false = move to trash
    val perItem: Boolean   // true = user picks each file
)

fun storageRoot(): File = Environment.getExternalStorageDirectory()

fun hasAccess(ctx: Context): Boolean =
    if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
    else ctx.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

fun fmtSize(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024L * 1024 -> "%.0f KB".format(b / 1024.0)
    b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / 1048576.0)
    else -> "%.2f GB".format(b / 1073741824.0)
}

fun fmtDate(t: Long): String = SimpleDateFormat("MMM d, yyyy", Locale.US).format(t)

fun shortPath(f: File): String =
    (f.parent ?: "").removePrefix(storageRoot().absolutePath).trimStart('/').ifEmpty { "Internal storage" }

private fun walk(dir: File, skipDir: (File) -> Boolean, onFile: (File) -> Unit) {
    val kids = dir.listFiles() ?: return
    for (f in kids) {
        if (f.isDirectory) {
            if (!skipDir(f)) walk(f, skipDir, onFile)
        } else onFile(f)
    }
}

private fun isAndroidDir(d: File) = d.name == "Android" && d.parentFile == storageRoot()

private fun hashFile(f: File, limit: Long = Long.MAX_VALUE): String {
    val md = MessageDigest.getInstance("SHA-256")
    FileInputStream(f).use { ins ->
        val buf = ByteArray(65536)
        var left = limit
        while (left > 0) {
            val n = ins.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) break
            md.update(buf, 0, n)
            left -= n
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

/** Date taken (EXIF) for JPEGs, else file modified time. */
fun fileDate(f: File): Long {
    val ext = f.extension.lowercase()
    if (ext == "jpg" || ext == "jpeg") {
        val s = runCatching { ExifInterface(f.absolutePath).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) }.getOrNull()
        if (s != null) {
            val t = runCatching { SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(s)?.time }.getOrNull()
            if (t != null) return t
        }
    }
    return f.lastModified()
}

private fun locationScore(f: File): Int {
    val p = f.absolutePath.lowercase(Locale.US)
    var s = 0
    if ("/dcim/camera/" in p) s += 50 else if ("/dcim/" in p) s += 30 else if ("/pictures/" in p) s += 10
    if ("/download" in p) s -= 20
    if ("whatsapp" in p) s -= 20
    if ("telegram" in p) s -= 20
    if ("/screenshots/" in p) s -= 15
    return s
}

/** First item = the copy to keep. */
fun rank(files: List<File>, keepOldest: Boolean): List<Item> {
    val items = files.map { Item(it, it.length(), fileDate(it)) }
    val byDate: Comparator<Item> =
        if (keepOldest) compareBy<Item> { it.date } else compareByDescending<Item> { it.date }
    val cmp = compareByDescending<Item> { locationScore(it.file) }
        .then(byDate)
        .thenBy { if (COPY_NAME.matches(it.file.name)) 1 else 0 }
    return items.sortedWith(cmp)
}

/** Exact duplicates by content: size -> quick hash (first 64 KB) -> full SHA-256. */
fun findDuplicates(keepOldest: Boolean): List<DupGroup> {
    val bySize = HashMap<Long, MutableList<File>>()
    walk(storageRoot(), { it.name.startsWith(".") || isAndroidDir(it) }) { f ->
        val ext = f.extension.lowercase()
        if ((ext in IMAGE_EXT || ext in VIDEO_EXT) && f.length() >= 1024) {
            bySize.getOrPut(f.length()) { mutableListOf() }.add(f)
        }
    }
    val groups = mutableListOf<DupGroup>()
    for ((_, files) in bySize) {
        if (files.size < 2) continue
        val quick = files.groupBy { runCatching { hashFile(it, 65536) }.getOrNull() }
        for ((qk, qf) in quick) {
            if (qk == null || qf.size < 2) continue
            val full = qf.groupBy { runCatching { hashFile(it) }.getOrNull() }
            for ((fk, ff) in full) {
                if (fk != null && ff.size >= 2) groups.add(DupGroup(rank(ff, keepOldest)))
            }
        }
    }
    return groups.sortedByDescending { g -> g.items.drop(1).sumOf { it.size } }
}

private fun collectEmpty(dir: File, out: MutableList<File>): Boolean {
    val kids = dir.listFiles() ?: return false
    var empty = true
    for (k in kids) {
        if (k.isDirectory) {
            if (!collectEmpty(k, out)) empty = false
        } else empty = false
    }
    if (empty) out.add(dir)
    return empty
}

fun findJunk(): List<JunkCat> {
    val now = System.currentTimeMillis()
    val dayMs = 24L * 3600 * 1000
    val apks = mutableListOf<Item>()
    val old = mutableListOf<Item>()
    val big = mutableListOf<Item>()
    val thumbs = mutableListOf<Item>()
    walk(storageRoot(), { isAndroidDir(it) || it.name == ".CleanerTrash" }) { f ->
        val ext = f.extension.lowercase()
        val p = f.absolutePath.lowercase(Locale.US)
        val size = f.length()
        val item = Item(f, size, f.lastModified())
        when {
            f.parentFile?.name == ".thumbnails" -> thumbs.add(item)
            ext == "apk" -> apks.add(item)
            ext in VIDEO_EXT && size >= 200L * 1024 * 1024 -> big.add(item)
            "/download/" in p && now - f.lastModified() > 90 * dayMs -> old.add(item)
        }
    }
    val emptyDirs = mutableListOf<File>()
    storageRoot().listFiles()?.forEach { top ->
        if (top.isDirectory && !isAndroidDir(top) && top.name != ".CleanerTrash") {
            val tmp = mutableListOf<File>()
            collectEmpty(top, tmp)
            // keep standard top-level folders such as Download or DCIM even when empty
            emptyDirs.addAll(tmp.filter { !(it.parentFile == storageRoot() && it.name in STANDARD_DIRS) })
        }
    }
    val empties = emptyDirs.map { Item(it, 0L, it.lastModified()) }
    return listOf(
        JunkCat("old", "Old downloads", "Downloads older than 90 days", old.sortedByDescending { it.size }, false, false),
        JunkCat("apk", "Installer files (APK)", "${apks.size} files", apks.sortedByDescending { it.size }, false, false),
        JunkCat("big", "Large videos", "Over 200 MB, you pick each one", big.sortedByDescending { it.size }, false, true),
        JunkCat("thumb", "Thumbnail caches", "Hidden .thumbnails folders", thumbs, true, false),
        JunkCat("empty", "Empty folders", "${empties.size} folders", empties, true, false)
    ).filter { it.items.isNotEmpty() }
}

data class TrashEntry(val name: String, val original: String, val time: Long)

/** Own trash folder on shared storage. Deleted items stay recoverable for the chosen number of days. */
class Trash(private val ctx: Context) {
    private val dir = File(storageRoot(), ".CleanerTrash")
    private val index = File(dir, ".index.tsv")

    private fun scan(paths: List<String>) {
        if (paths.isNotEmpty()) MediaScannerConnection.scanFile(ctx, paths.toTypedArray(), null, null)
    }

    fun entries(): List<TrashEntry> {
        if (!index.exists()) return emptyList()
        return index.readLines().mapNotNull { line ->
            val p = line.split("\t")
            if (p.size == 3) TrashEntry(p[0], p[1], p[2].toLongOrNull() ?: 0L) else null
        }
    }

    private fun writeEntries(list: List<TrashEntry>) {
        dir.mkdirs()
        index.writeText(list.joinToString("") { "${it.name}\t${it.original}\t${it.time}\n" })
    }

    fun info(): Pair<Int, Long> {
        val e = entries().filter { File(dir, it.name).exists() }
        return e.size to e.sumOf { File(dir, it.name).length() }
    }

    fun moveToTrash(files: List<File>): Pair<Int, Long> {
        dir.mkdirs()
        val list = entries().toMutableList()
        var count = 0
        var bytes = 0L
        val moved = mutableListOf<String>()
        for (f in files) {
            if (!f.exists() || f.isDirectory) continue
            val size = f.length()
            val t = File(dir, "${System.currentTimeMillis()}_${count}_${f.name}")
            val ok = f.renameTo(t) || runCatching { f.copyTo(t); f.delete() }.getOrDefault(false)
            if (ok) {
                list.add(TrashEntry(t.name, f.absolutePath, System.currentTimeMillis()))
                moved.add(f.absolutePath)
                count++
                bytes += size
            }
        }
        writeEntries(list)
        scan(moved)
        return count to bytes
    }

    fun restoreAll(): Int {
        var n = 0
        val keep = mutableListOf<TrashEntry>()
        val restored = mutableListOf<String>()
        for (e in entries()) {
            val src = File(dir, e.name)
            var target = File(e.original)
            if (!src.exists()) continue
            target.parentFile?.mkdirs()
            if (target.exists()) target = File(target.parentFile, "restored_" + target.name)
            if (src.renameTo(target)) { n++; restored.add(target.absolutePath) } else keep.add(e)
        }
        writeEntries(keep)
        scan(restored)
        return n
    }

    fun emptyAll() {
        dir.listFiles()?.forEach { it.delete() }
    }

    fun purge(days: Int) {
        val limit = System.currentTimeMillis() - days * 24L * 3600 * 1000
        val keep = mutableListOf<TrashEntry>()
        for (e in entries()) {
            if (e.time < limit) File(dir, e.name).delete() else keep.add(e)
        }
        if (index.exists()) writeEntries(keep)
    }
}
