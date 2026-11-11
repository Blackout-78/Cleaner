package com.blackout.cleaner

import android.app.Application
import android.content.Context
import android.os.StatFs
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CleanerVM(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("cleaner", Context.MODE_PRIVATE)
    private val trash = Trash(app)

    var keepOldest by mutableStateOf(prefs.getBoolean("keepOldest", true)); private set
    var trashDays by mutableIntStateOf(prefs.getInt("trashDays", 30)); private set
    var scanning by mutableStateOf(false); private set
    var scanned by mutableStateOf(false); private set
    var status by mutableStateOf(""); private set
    var groups by mutableStateOf<List<DupGroup>>(emptyList()); private set
    var junk by mutableStateOf<List<JunkCat>>(emptyList()); private set
    var removeSet by mutableStateOf<Set<String>>(emptySet()); private set
    var junkSet by mutableStateOf<Set<String>>(emptySet()); private set
    var trashInfo by mutableStateOf(0 to 0L); private set

    init { refreshTrash() }

    fun storage(): Pair<Long, Long> {
        val s = StatFs(storageRoot().path)
        return s.blockCountLong * s.blockSizeLong to s.availableBlocksLong * s.blockSizeLong
    }

    fun refreshTrash() {
        viewModelScope.launch {
            trashInfo = withContext(Dispatchers.IO) { trash.purge(trashDays); trash.info() }
        }
    }

    fun setKeepOldest(v: Boolean) {
        keepOldest = v
        prefs.edit().putBoolean("keepOldest", v).apply()
        groups = groups.map { DupGroup(rank(it.items.map { i -> i.file }, v)) }
        removeSet = groups.flatMap { it.items.drop(1) }.map { it.path }.toSet()
    }

    fun setTrashDays(d: Int) {
        trashDays = d
        prefs.edit().putInt("trashDays", d).apply()
        refreshTrash()
    }

    fun dupBytes(): Long = groups.sumOf { g -> g.items.filter { it.path in removeSet }.sumOf { it.size } }
    fun dupCount(): Int = groups.sumOf { g -> g.items.count { it.path in removeSet } }
    fun junkBytes(): Long = junk.sumOf { c -> c.items.filter { it.path in junkSet }.sumOf { it.size } }
    fun junkCount(): Int = junk.sumOf { c -> c.items.count { it.path in junkSet } }

    fun scan() {
        if (scanning) return
        scanning = true
        viewModelScope.launch {
            try {
                status = "Scanning for duplicates..."
                val g = withContext(Dispatchers.IO) { findDuplicates(keepOldest) }
                groups = g
                removeSet = g.flatMap { it.items.drop(1) }.map { it.path }.toSet()
                status = "Scanning for junk..."
                val j = withContext(Dispatchers.IO) { findJunk() }
                junk = j
                junkSet = j.filter { !it.perItem }.flatMap { it.items }.map { it.path }.toSet()
                scanned = true
                status = "Scan finished"
            } catch (e: Exception) {
                status = "Scan failed: ${e.message}"
            } finally {
                scanning = false
            }
        }
    }

    fun toggleDup(group: DupGroup, item: Item) {
        if (item.path in removeSet) {
            removeSet = removeSet - item.path
        } else {
            // always keep at least one copy
            val stillKept = group.items.count { it.path !in removeSet && it.path != item.path }
            if (stillKept >= 1) removeSet = removeSet + item.path
        }
    }

    fun cleanDuplicates() {
        val files = groups.flatMap { it.items }.filter { it.path in removeSet }.map { it.file }
        if (files.isEmpty()) return
        viewModelScope.launch {
            val (n, bytes) = withContext(Dispatchers.IO) { trash.moveToTrash(files) }
            groups = groups.map { g -> DupGroup(g.items.filter { it.file.exists() }) }.filter { it.items.size >= 2 }
            removeSet = groups.flatMap { it.items.drop(1) }.map { it.path }.toSet()
            status = "Moved $n files to Trash (${fmtSize(bytes)})"
            trashInfo = withContext(Dispatchers.IO) { trash.info() }
        }
    }

    fun toggleJunkCat(cat: JunkCat) {
        val paths = cat.items.map { it.path }
        junkSet = if (paths.all { it in junkSet }) junkSet - paths.toSet() else junkSet + paths
    }

    fun toggleJunkItem(item: Item) {
        junkSet = if (item.path in junkSet) junkSet - item.path else junkSet + item.path
    }

    fun cleanJunk() {
        val cats = junk
        val picked = junkSet
        viewModelScope.launch {
            var bytes = 0L
            var n = 0
            withContext(Dispatchers.IO) {
                for (c in cats) {
                    val sel = c.items.filter { it.path in picked }
                    if (sel.isEmpty()) continue
                    if (c.direct) {
                        sel.forEach { if (it.file.delete()) { n++; bytes += it.size } }
                    } else {
                        val (cnt, b) = trash.moveToTrash(sel.map { it.file })
                        n += cnt; bytes += b
                    }
                }
            }
            junk = junk.map { c -> c.copy(items = c.items.filter { it.file.exists() }) }.filter { it.items.isNotEmpty() }
            junkSet = junkSet.filter { p -> junk.any { c -> c.items.any { it.path == p } } }.toSet()
            status = "Cleaned $n items (${fmtSize(bytes)})"
            trashInfo = withContext(Dispatchers.IO) { trash.info() }
        }
    }

    fun restoreTrash() {
        viewModelScope.launch {
            val n = withContext(Dispatchers.IO) { trash.restoreAll() }
            status = "Restored $n files"
            trashInfo = withContext(Dispatchers.IO) { trash.info() }
        }
    }

    fun emptyTrash() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { trash.emptyAll() }
            status = "Trash emptied"
            trashInfo = withContext(Dispatchers.IO) { trash.info() }
        }
    }
}
