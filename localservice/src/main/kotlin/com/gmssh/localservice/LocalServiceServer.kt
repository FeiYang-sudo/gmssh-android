package com.gmssh.localservice

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import fi.iki.elonen.NanoHTTPD
import java.io.File

/**
 * GMSSH 本地服务（NanoHTTPD 实现）。
 *
 * - 监听 127.0.0.1:<port>，只对本机可见
 * - 统一响应信封 `{code, msg, data}`（契约见 API_SPEC.md）
 * - 全量 CORS + OPTIONS 预检（页面来自 appassets/其他端口，属跨域）
 */
class LocalServiceServer(
    host: String,
    port: Int,
    private val store: Store,
) : NanoHTTPD(host, port) {

    companion object {
        private const val TAG = "GmServer"
        private const val MAX_BODY = 4 * 1024 * 1024
        const val DEFAULT_HOST = "127.0.0.1"
    }

    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

    /* ------------------------------------------------------------------ */
    /* 返回体构造                                                          */
    /* ------------------------------------------------------------------ */

    private fun ok(data: Any? = null, msg: String = "ok"): Response {
        val o = JsonObject().apply {
            addProperty("code", 0)
            addProperty("msg", msg)
            add("data", if (data == null) com.google.gson.JsonNull.INSTANCE else gson.toJsonTree(data))
        }
        return jsonResponse(Response.Status.OK, o)
    }

    private fun fail(code: Int, msg: String, detail: String? = null, http: Response.Status = Response.Status.OK): Response {
        val o = JsonObject().apply {
            addProperty("code", code)
            addProperty("msg", msg)
            if (detail != null) addProperty("detail", detail)
            add("data", com.google.gson.JsonNull.INSTANCE)
        }
        return jsonResponse(http, o)
    }

    private fun unimplemented(path: String, method: String): Response {
        Log.w(TAG, "UNIMPLEMENTED $method $path")
        return fail(501, "本地服务尚未实现该接口：$path", "UNIMPLEMENTED $method $path", Response.Status.NOT_IMPLEMENTED)
    }

    private fun jsonResponse(status: Response.Status, body: JsonObject): Response {
        val r = newFixedLengthResponse(status, "application/json; charset=utf-8", gson.toJson(body))
        r.addHeader("Cache-Control", "no-store")
        return r
    }

    private fun applyCors(r: Response, origin: String?) {
        if (!origin.isNullOrEmpty() && origin != "null") {
            r.addHeader("Access-Control-Allow-Origin", origin)
            r.addHeader("Access-Control-Allow-Credentials", "true")
            r.addHeader("Vary", "Origin")
        } else {
            r.addHeader("Access-Control-Allow-Origin", "*")
        }
        r.addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS, HEAD")
        r.addHeader(
            "Access-Control-Allow-Headers",
            "Content-Type, Authorization, lang, jm-language, X-Requested-With, Accept, Origin, Cache-Control, Pragma"
        )
        r.addHeader("Access-Control-Max-Age", "86400")
    }

    /* ------------------------------------------------------------------ */
    /* 请求入口                                                            */
    /* ------------------------------------------------------------------ */

    override fun serve(session: IHTTPSession): Response {
        val path = session.uri ?: "/"
        val method = session.method?.name ?: "GET"
        val origin = session.headers["origin"]

        // 预检：直接 204
        if (method == "OPTIONS") {
            val r = newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "")
            applyCors(r, origin)
            return r
        }

        val resp = try {
            route(method, path, session)
        } catch (t: Throwable) {
            Log.e(TAG, "handler crash on $method $path: ${t.javaClass.name}: ${t.message}")
            fail(500, "本地服务内部错误：${t.message ?: t.javaClass.simpleName}")
        }
        applyCors(resp, origin)
        return resp
    }

    private fun route(method: String, path: String, session: IHTTPSession): Response {
        val body = readBody(session)
        val payload: JsonObject = parseJson(body)

        Log.i(TAG, "$method $path" + if (payload.size() > 0) " keys=${payload.keySet()}" else "")

        return when (path) {
            /* ---------- 访问门 ---------- */
            "/data/status" -> ok(mapOf("encrypted" to false, "hasPassword" to false))
            "/data/open" -> ok(null, "ok")
            "/data/setPassword", "/data/removePassword", "/data/reset",
            "/data/validateRawPassword" -> ok(null, "ok")

            /* ---------- 机器（node） ---------- */
            "/node/list" -> {
                val groupId = payload.str("groupId")
                val cond = payload.str("cond")
                val pageNum = payload.int("pageNum", 1).coerceAtLeast(1)
                val pageSize = payload.int("pageSize", 10).let { if (it <= 0) 10 else it }
                ok(store.listNodes(groupId.ifEmpty { null }, cond.ifEmpty { null }, pageNum, pageSize))
            }

            "/node/add" -> {
                val node = store.addNode(payload)
                ok(mapOf("id" to node.str("id")), "添加机器成功")
            }

            "/node/edit" -> {
                val merged = store.editNode(payload)
                    ?: return fail(2, "机器不存在，无法编辑")
                ok(mapOf("id" to merged.str("id")), "编辑机器成功")
            }

            "/node/delete" -> {
                val id = payload.str("id")
                if (id.isEmpty()) return fail(2, "缺少 id")
                if (!store.deleteNode(id)) return fail(2, "机器不存在")
                ok(null, "删除成功")
            }

            "/node/detail" -> {
                val id = payload.str("id")
                val node = store.findNode(id) ?: return fail(2, "机器不存在")
                ok(node)
            }

            "/node/proxy/detail" -> ok(JsonObject())

            /* ---------- 真 SSH 测试连接 ---------- */
            "/node/test" -> handleNodeTest(payload)

            /* ---------- 空实现（保持 UI 主流程不被 toast 打断） ---------- */
            "/node/bastionList" -> ok(mapOf("list" to emptyList<Any>(), "total" to 0))
            "/node/autoRefresh" -> ok(mapOf("enabled" to false))
            "/node/autoRefresh/toggle" -> ok(mapOf("enabled" to false))
            "/node/sort", "/node/setTop", "/node/refreshInfo", "/node/check-boot",
            "/node/reboot", "/node/clearAuthCache", "/node/updateOSName",
            "/node/sudoPassword/save" -> {
                Log.i(TAG, "STUB ok: $path")
                ok(null, "ok")
            }

            /* ---------- 分组 ---------- */
            "/group/list" -> ok(store.listGroups())
            "/group/add" -> {
                val name = payload.str("name").ifEmpty { return fail(2, "分组名称不能为空") }
                ok(store.addGroup(name), "添加成功")
            }
            "/group/edit" -> {
                val id = payload.str("id")
                if (!store.editGroup(id, payload.str("name"))) fail(2, "分组不存在") else ok(null, "编辑成功")
            }
            "/group/delete" -> {
                val id = payload.str("id")
                if (!store.deleteGroup(id)) fail(2, "分组不存在") else ok(null, "删除成功")
            }
            "/group/setOrder" -> {
                Log.i(TAG, "STUB ok: /group/setOrder")
                ok(null, "ok")
            }

            /* ---------- 设置 ---------- */
            "/settings/get" -> ok(store.getSettings())

            /* ---------- 云端服务器列表（server） ---------- */
            "/server/list" -> ok(store.listServers())
            "/server/add" -> {
                val s = store.addServer(payload)
                ok(mapOf("id" to s.str("id")), "添加成功")
            }
            "/server/edit" -> {
                val s = store.editServer(payload) ?: return fail(2, "服务器不存在")
                ok(mapOf("id" to s.str("id")), "编辑成功")
            }
            "/server/delete" -> {
                if (!store.deleteServer(payload.str("id"))) fail(2, "服务器不存在") else ok(null, "删除成功")
            }
            "/server/updateLocal" -> {
                Log.i(TAG, "STUB ok: /server/updateLocal")
                ok(null, "ok")
            }

            /* ---------- 健康检查（自加，非前端契约） ---------- */
            "/__health" -> ok(mapOf("status" to "up", "nodes" to store.allNodes().size))
            // 前端启动即请求语言包；没有会报 501 + 未知错误
            // 注意：外层是 `when (path)` 的取值形式，这里不能直接写 Boolean 分支
            else -> if (path.startsWith("/web/lang/")) ok(mapOf<String, Any>()) else unimplemented(path, method)
        }
    }

    /* ------------------------------------------------------------------ */
    /* /node/test                                                          */
    /* ------------------------------------------------------------------ */

    private fun handleNodeTest(payload: JsonObject): Response {
        val hostObj = payload.obj("host")
        val host = hostObj?.str("ip")?.ifEmpty { null }
            ?: payload.str("ip").ifEmpty { payload.str("host") }
        val portRaw = hostObj?.str("port")?.ifEmpty { null } ?: payload.str("port")
        val port = portRaw.toIntOrNull() ?: 22
        val user = payload.str("userName").ifEmpty { payload.str("user") }.ifEmpty { "root" }
        val authType = payload.str("authType").ifEmpty { "pass" }
        val authValue = payload.str("authValue").ifEmpty { payload.str("password") }
        val keyPass = payload.str("keyPass").ifEmpty { null }

        if (host.isBlank()) return fail(2, "Host 地址不能为空")
        if (port !in 1..65535) return fail(2, "端口不合法：$port")

        val r = SshTester.test(host, port, user, authType, authValue, keyPass)
        return if (r.ok) {
            ok(
                mapOf(
                    "ok" to true,
                    "host" to host,
                    "port" to port,
                    "userName" to user,
                    "authType" to authType,
                    "detail" to r.detail,
                ),
                r.message
            )
        } else {
            fail(2, r.message, r.detail)
        }
    }

    /* ------------------------------------------------------------------ */
    /* 工具                                                               */
    /* ------------------------------------------------------------------ */

    private fun readBody(session: IHTTPSession): String {
        val len = session.headers["content-length"]?.toLongOrNull() ?: 0L
        if (len <= 0L) return ""
        if (len > MAX_BODY) {
            Log.w(TAG, "body too large: $len")
            return ""
        }
        val buf = ByteArray(len.toInt())
        var read = 0
        val ins = session.inputStream ?: return ""
        while (read < buf.size) {
            val r = try {
                ins.read(buf, read, buf.size - read)
            } catch (e: Throwable) {
                -1
            }
            if (r <= 0) break
            read += r
        }
        return String(buf, 0, read, Charsets.UTF_8)
    }

    private fun parseJson(body: String): JsonObject {
        if (body.isBlank()) return JsonObject()
        return try {
            val el = JsonParser.parseString(body)
            if (el.isJsonObject) el.asJsonObject else JsonObject()
        } catch (e: Throwable) {
            Log.w(TAG, "bad json body: ${e.message}")
            JsonObject()
        }
    }
}
