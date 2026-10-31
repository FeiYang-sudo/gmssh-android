package com.gmssh.localservice

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 机器/分组/云端服务器仓库。
 *
 * 纯 JSON 文件持久化：`<baseDir>/servers.json`
 * 结构：
 * {
 *   "seq": 3,
 *   "nodes":   [ {...node..., "id":"n1"}, ... ],
 *   "groups":  [ {"id":"g1","name":"默认"}, ... ],
 *   "servers": [ ... 云端服务器缓存 ... ],
 *   "settings": {...}
 * }
 *
 * 节点对象按「前端提交的 JSON 原样 + id/时间戳」保存，避免字段漂移 ——
 * 前端读取的字段（name/ip/port/userName/host/authType/...）全都还在。
 */
class Store(baseDir: File) {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val dir: File = baseDir.apply { mkdirs() }
    private val file = File(dir, "servers.json")
    private val lock = Any()

    private var seq: Long = 1
    private val nodes = JsonArray()
    private val groups = JsonArray()
    private val servers = JsonArray()
    private var settings: JsonObject = JsonObject()

    init {
        load()
    }

    private fun load() {
        if (!file.exists()) return
        try {
            val root = gson.fromJson(file.readText(Charsets.UTF_8), JsonObject::class.java) ?: return
            seq = root.get("seq")?.asLong ?: 1L
            root.getAsJsonArray("nodes")?.let { arr -> arr.forEach { nodes.add(it) } }
            root.getAsJsonArray("groups")?.let { arr -> arr.forEach { groups.add(it) } }
            root.getAsJsonArray("servers")?.let { arr -> arr.forEach { servers.add(it) } }
            root.getAsJsonObject("settings")?.let { settings = it }
            Log.i(TAG, "loaded ${nodes.size()} nodes / ${groups.size()} groups from ${file.absolutePath}")
        } catch (t: Throwable) {
            Log.w(TAG, "load failed, starting empty: ${t.message}")
        }
    }

    private fun persist() {
        synchronized(lock) {
            val root = JsonObject().apply {
                addProperty("seq", seq)
                add("nodes", nodes)
                add("groups", groups)
                add("servers", servers)
                add("settings", settings)
            }
            val tmp = File(dir, "servers.json.tmp")
            tmp.writeText(gson.toJson(root), Charsets.UTF_8)
            try {
                Files.move(
                    tmp.toPath(), file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE
                )
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    private fun nextId(prefix: String): String {
        val id = prefix + seq
        seq += 1
        return id
    }

    /* ---------------- nodes ---------------- */

    fun listNodes(groupId: String?, cond: String?, pageNum: Int, pageSize: Int): JsonObject {
        val all = nodes.toList()
        val filtered = all.filter { el ->
            val o = el.asJsonObject
            val gOk = groupId.isNullOrEmpty() || o.str("groupId") == groupId
            val kOk = cond.isNullOrEmpty() || run {
                val k = cond!!.lowercase()
                o.str("name").lowercase().contains(k) ||
                    o.str("ip").lowercase().contains(k) ||
                    o.str("userName").lowercase().contains(k)
            }
            gOk && kOk
        }
        val from = ((pageNum - 1) * pageSize).coerceAtLeast(0)
        val page = if (from >= filtered.size) emptyList() else filtered.drop(from).take(pageSize)
        return JsonObject().apply {
            addProperty("total", filtered.size)
            add("list", JsonArray().apply { page.forEach { add(it) } })
        }
    }

    /** 全量节点（内部用，比如按 id 查） */
    fun allNodes(): List<JsonObject> = nodes.map { it.asJsonObject }

    fun findNode(id: String): JsonObject? =
        nodes.map { it.asJsonObject }.firstOrNull { it.str("id") == id }

    /**
     * 新增节点。[payload] 原样保存；会补 id / createTime / updateTime。
     * 返回带 id 的完整对象。
     */
    fun addNode(payload: JsonObject): JsonObject {
        val obj = payload.deepCopy()
        if (obj.str("id").isEmpty()) obj.addProperty("id", nextId("n"))
        stripSecrets(obj)
        val now = System.currentTimeMillis()
        obj.addProperty("createTime", now)
        obj.addProperty("updateTime", now)
        synchronized(lock) {
            nodes.add(obj)
            persist()
        }
        Log.i(TAG, "node added: ${obj.str("id")} ${obj.str("name")}")
        return obj
    }

    fun editNode(payload: JsonObject): JsonObject? {
        val id = payload.str("id")
        if (id.isEmpty()) return null
        synchronized(lock) {
            for (i in 0 until nodes.size()) {
                val cur = nodes[i].asJsonObject
                if (cur.str("id") != id) continue
                val merged = cur.deepCopy()
                payload.entrySet().forEach { (k, v) ->
                    if (k == "id") return@forEach
                    if (v == null || v.isJsonNull) return@forEach
                    merged.add(k, v)
                }
                stripSecrets(merged)
                merged.addProperty("updateTime", System.currentTimeMillis())
                nodes.set(i, merged)
                persist()
                Log.i(TAG, "node edited: $id")
                return merged
            }
        }
        return null
    }

    fun deleteNode(id: String): Boolean {
        synchronized(lock) {
            for (i in 0 until nodes.size()) {
                if (nodes[i].asJsonObject.str("id") == id) {
                    nodes.remove(i)
                    persist()
                    Log.i(TAG, "node deleted: $id")
                    return true
                }
            }
        }
        return false
    }

    /**
     * 凭据不落盘到明文之外的地方 —— 目前仍需要保存密码/私钥以便后续连接，
     * 但只在归档对象里保留 `authValue`，并去掉 sudo 相关临时字段。
     */
    private fun stripSecrets(o: JsonObject) {
        o.remove("sudoPassword")
        o.remove("noErrorTip")
    }

    /* ---------------- groups ---------------- */

    fun listGroups(): JsonObject = JsonObject().apply {
        add("list", JsonArray().apply { groups.forEach { add(it) } })
    }

    fun addGroup(name: String): String {
        synchronized(lock) {
            val id = nextId("g")
            groups.add(JsonObject().apply {
                addProperty("id", id)
                addProperty("name", name)
            })
            persist()
            return id
        }
    }

    fun editGroup(id: String, name: String): Boolean {
        synchronized(lock) {
            for (i in 0 until groups.size()) {
                val g = groups[i].asJsonObject
                if (g.str("id") == id) {
                    g.addProperty("name", name)
                    persist()
                    return true
                }
            }
        }
        return false
    }

    fun deleteGroup(id: String): Boolean {
        synchronized(lock) {
            for (i in 0 until groups.size()) {
                if (groups[i].asJsonObject.str("id") == id) {
                    groups.remove(i)
                    persist()
                    return true
                }
            }
        }
        return false
    }

    /* ---------------- servers（云端列表缓存） ---------------- */

    fun listServers(): JsonObject = JsonObject().apply {
        addProperty("total", servers.size())
        add("list", JsonArray().apply { servers.forEach { add(it) } })
        add("servers", JsonArray().apply { servers.forEach { add(it) } })
    }

    fun addServer(payload: JsonObject): JsonObject {
        val obj = payload.deepCopy()
        if (obj.str("id").isEmpty()) obj.addProperty("id", nextId("s"))
        obj.addProperty("createTime", System.currentTimeMillis())
        synchronized(lock) {
            servers.add(obj)
            persist()
        }
        return obj
    }

    fun editServer(payload: JsonObject): JsonObject? {
        val id = payload.str("id")
        if (id.isEmpty()) return null
        synchronized(lock) {
            for (i in 0 until servers.size()) {
                val cur = servers[i].asJsonObject
                if (cur.str("id") != id) continue
                val merged = cur.deepCopy()
                payload.entrySet().forEach { (k, v) -> if (k != "id") merged.add(k, v) }
                servers.set(i, merged)
                persist()
                return merged
            }
        }
        return null
    }

    fun deleteServer(id: String): Boolean {
        synchronized(lock) {
            for (i in 0 until servers.size()) {
                if (servers[i].asJsonObject.str("id") == id) {
                    servers.remove(i); persist(); return true
                }
            }
        }
        return false
    }

    /* ---------------- settings ---------------- */

    fun getSettings(): JsonObject = settings

    fun setSettings(s: JsonObject) {
        synchronized(lock) { settings = s; persist() }
    }

    companion object {
        private const val TAG = "GmStore"
    }
}

/* ---------------- JSON 小工具 ---------------- */

internal fun JsonObject.str(key: String): String {
    val e: JsonElement = this.get(key) ?: return ""
    return if (e.isJsonNull) "" else runCatching { e.asString }.getOrDefault("")
}

internal fun JsonObject.int(key: String, def: Int = 0): Int {
    val e = this.get(key) ?: return def
    return if (e.isJsonNull) def else runCatching { e.asInt }.getOrDefault(def)
}

internal fun JsonObject.bool(key: String, def: Boolean = false): Boolean {
    val e = this.get(key) ?: return def
    return if (e.isJsonNull) def else runCatching { e.asBoolean }.getOrDefault(def)
}

internal fun JsonObject.obj(key: String): JsonObject? =
    this.get(key)?.takeIf { it.isJsonObject }?.asJsonObject
