#!/usr/bin/env python3
"""等 gmssh-android 构建：成功则下载 APK，失败则抓失败步骤 + 日志片段。"""
import io, json, os, subprocess, time, urllib.request

TOK = [l.split("=", 1)[1].strip() for l in io.open("/root/.hermes/.env", encoding="utf-8")
       if l.startswith("GITHUB_TOKEN=")][0]
REPO = "FeiYang-sudo/gmssh-android"
OUT = "/root/gmssh_android/gmssh-p1.apk"


def gh(path):
    req = urllib.request.Request("https://api.github.com" + path)
    req.add_header("Authorization", "token " + TOK)
    req.add_header("User-Agent", "hermes")
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read() or b"{}")


def main():
    runs = gh(f"/repos/{REPO}/actions/runs?per_page=3").get("workflow_runs", [])
    if not runs:
        print("没有构建任务")
        return
    run = runs[0]
    print("监控 run:", run["run_number"], run["html_url"], flush=True)

    deadline = time.time() + 2400
    while time.time() < deadline:
        st = gh(f"/repos/{REPO}/actions/runs/{run['id']}")
        if st["status"] == "completed":
            run = st
            break
        print("  …", st["status"], flush=True)
        time.sleep(45)

    print("结果:", run["status"], run["conclusion"], flush=True)

    if run["conclusion"] != "success":
        jobs = gh(f"/repos/{REPO}/actions/runs/{run['id']}/jobs")
        for j in jobs.get("jobs", []):
            for s in j.get("steps", []):
                if s.get("conclusion") == "failure":
                    print("失败步骤:", s["name"], flush=True)
        print("日志页:", run["html_url"])
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
        print("Release 无 APK")
        return
    tag, asset = target
    print(f"下载 {tag}/{asset['name']} {asset['size']/1048576:.2f} MB", flush=True)
    url = f"https://gh-proxy.com/https://github.com/{REPO}/releases/download/{tag}/{asset['name']}"
    r = subprocess.run(["curl", "-sL", "--fail", "--max-time", "600", "-o", OUT, url],
                       capture_output=True, text=True)
    if r.returncode != 0:
        print("下载失败:", r.stderr[-200:])
        return
    print(f"已存 {OUT} {os.path.getsize(OUT)/1048576:.2f} MB")
    z = subprocess.run(["bash", "-c", f"unzip -l {OUT} | grep -E 'classes.*dex|assets/gm_shim' "],
                       capture_output=True, text=True)
    print("包内关键文件:\n" + (z.stdout or ""))
    print("DONE")


main()
