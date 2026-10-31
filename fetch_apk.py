#!/usr/bin/env python3
"""等 gmssh-android 的 Actions 构建完成 → 自动下载 APK → 校验。"""
import io, json, os, subprocess, time, urllib.request

TOK = [l.split("=", 1)[1].strip() for l in io.open("/root/.hermes/.env", encoding="utf-8")
       if l.startswith("GITHUB_TOKEN=")][0]
REPO = "FeiYang-sudo/gmssh-android"
OUT = "/root/gmssh_android/gmssh-desktop.apk"


def gh(path):
    req = urllib.request.Request("https://api.github.com" + path)
    req.add_header("Authorization", "token " + TOK)
    req.add_header("User-Agent", "hermes")
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read() or b"{}")


def main():
    deadline = time.time() + 3600
    run = None
    while time.time() < deadline:
        runs = gh(f"/repos/{REPO}/actions/runs?per_page=5").get("workflow_runs", [])
        run = runs[0] if runs else None
        if run and run["status"] == "completed":
            break
        print("  …构建中", run["status"] if run else "等待启动", flush=True)
        time.sleep(40)

    if not run:
        print("没找到构建任务")
        return
    print("构建结束:", run["conclusion"], flush=True)
    if run["conclusion"] != "success":
        print("失败日志:", run["html_url"])
        # 拉失败步骤的最后输出
        try:
            jobs = gh(f"/repos/{REPO}/actions/runs/{run['id']}/jobs")
            for j in jobs.get("jobs", []):
                for st in j.get("steps", []):
                    if st.get("conclusion") == "failure":
                        print("  失败步骤:", st.get("name"))
        except Exception as e:
            print("  读日志失败:", e)
        return

    rels = gh(f"/repos/{REPO}/releases?per_page=5")
    target = None
    for rel in rels:
        for a in rel.get("assets", []):
            if a["name"].endswith(".apk"):
                target = (rel["tag_name"], a)
                break
        if target:
            break
    if not target:
        print("Release 里没有 APK")
        return
    tag, asset = target
    print(f"  取回 {tag}/{asset['name']}  {asset['size']/1048576:.2f} MB", flush=True)
    url = f"https://gh-proxy.com/https://github.com/{REPO}/releases/download/{tag}/{asset['name']}"
    r = subprocess.run(["curl", "-L", "--fail", "--max-time", "600", "-o", OUT, url],
                       capture_output=True, text=True)
    if r.returncode != 0:
        print("下载失败:", r.stderr[-200:])
        return
    print(f"  已保存 {OUT}  {os.path.getsize(OUT)/1048576:.2f} MB", flush=True)
    z = subprocess.run(["unzip", "-l", OUT], capture_output=True, text=True)
    print("  APK 内容抽样:")
    for line in (z.stdout or "").splitlines()[:8]:
        print("   ", line[:90])
    aapt = subprocess.run(["bash", "-c",
        f"unzip -p {OUT} AndroidManifest.xml | strings | grep -iE 'com\\.|versionName' | head -5"],
        capture_output=True, text=True)
    print("  manifest 抽样:", (aapt.stdout or "").replace("\n", " ")[:200])
    print("DONE")


main()
