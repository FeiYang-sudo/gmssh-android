"""同源验证：页面由 localservice 的 browserPort 自己托管（http://127.0.0.1:8896）。

模拟 Android WebView：document-start 注入 gm_shim.js + 桩 AndroidBridge（get-port 返回 8896）。
期望：页面出现「暂无服务器 / 立即添加」，且所有 XHR 打的是同源 http://127.0.0.1:8896。
"""
import asyncio
import json
import pathlib

from playwright.async_api import async_playwright

BASE = "http://127.0.0.1:8896"
URL = f"{BASE}/index.html"
SHIM = pathlib.Path("/root/gmssh_android/app/src/main/assets/gm_shim.js").read_text()
SHOT = "/root/gmssh_android/verify_same_origin.png"

# 与 MainActivity.AndroidBridge.invoke 同语义的桩
BRIDGE_STUB = """
window.AndroidBridge = {
  invoke: function (id, payloadJson) {
    var p = {}; try { p = JSON.parse(payloadJson || '{}'); } catch (e) {}
    var t = (p && p.type) || '';
    var portObj = '{"gmPort":8896,"multiPort":8896,"browserPort":8896,"gmServerStatus":true,"error":null}';
    if (t === 'get-port' || t === 'startGMServer' || t === 'start-gm-server' ||
        t === 'start-gm-server-and-get-port') { return portObj; }
    if (t === 'get-build-env') { return '"pro"'; }
    if (t === 'get-app-exit-to-tray') { return '{"status":"ok","data":false}'; }
    if (t === 'get-resource-versions') { return '{"status":"ok","data":{}}'; }
    if (t === 'check-update') { return '{"status":"ok","update":false,"message":"已是最新版本"}'; }
    return '{"status":"ok"}';
  },
  isNetworkAvailable: function () { return true; },
  platformInfo: function () {
    return JSON.stringify({node:'20.0.0',chrome:'126.0.6478.71',electron:'31.0.0',
      platform:'android',arch:'arm64',distPath:'/android_asset/dist'});
  },
  log: function (m) { console.log('[bridge.log]', m); },
  toast: function (m) { console.log('[bridge.toast]', m); },
  diag: function (m) { console.log('[bridge.diag]', m); }
};
"""


async def main():
    logs, reqs, fails, bad = [], [], [], []
    async with async_playwright() as p:
        b = await p.chromium.launch(args=["--no-sandbox"])
        pg = await b.new_page(viewport={"width": 1440, "height": 900})

        def on_console(m):
            logs.append(f"{m.type}: {m.text[:180]}")

        def on_req(r):
            if r.url.startswith(BASE):
                reqs.append(f"{r.method} {r.url[len(BASE):]}")
            else:
                reqs.append(r.url[:140])

        def on_fail(r):
            fails.append(f"{r.url[:130]} :: {r.failure}")

        def on_resp(r):
            if r.status >= 400:
                bad.append(f"{r.status} {r.url[:130]}")

        pg.on("console", on_console)
        pg.on("request", on_req)
        pg.on("requestfailed", on_fail)
        pg.on("response", on_resp)

        await pg.add_init_script(BRIDGE_STUB)
        await pg.add_init_script(SHIM)

        try:
            await pg.goto(URL, wait_until="networkidle", timeout=45000)
        except Exception as e:
            print("goto 异常:", str(e)[:200])
        await pg.wait_for_timeout(5000)
        await pg.screenshot(path=SHOT, full_page=False)

        body = (await pg.inner_text("body")).replace("\n", " | ")
        print("标题:", await pg.title())
        print("URL:", pg.url)
        print("可见文本:", body[:600])
        print("含『暂无服务器』:", "暂无服务器" in body)
        print("含『立即添加』:", "立即添加" in body)
        print("\n--- 请求（去重，前 25）---")
        seen = []
        for u in reqs:
            if u not in seen:
                seen.append(u)
        for u in seen[:25]:
            print(" ", u)
        print("\n--- 失败请求 ---")
        for f in fails[:10]:
            print(" ", f)
        print("\n--- 4xx/5xx ---")
        for x in bad[:10]:
            print(" ", x)
        print("\n--- 控制台（前 12）---")
        for l in logs[:12]:
            print(" ", l)
        await b.close()

        ok = "暂无服务器" in body and "立即添加" in body and not bad
        print("\nRESULT:", "PASS" if ok else "FAIL")
        print(json.dumps({"shot": SHOT, "snippet": body[:300]}, ensure_ascii=False))


asyncio.run(main())
