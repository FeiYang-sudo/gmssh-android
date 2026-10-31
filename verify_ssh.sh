#!/bin/bash
set -o pipefail
cd /root/gmssh_android || exit 1

echo "=== ① 编译"
./gradlew :localservice:installDist --console=plain 2>&1 | grep -E "^e:|error:|BUILD" | head -8
if ! ls localservice/build/install/localservice/lib/*.jar >/dev/null 2>&1; then
  echo "编译产物不存在，退出"; exit 1
fi

echo "=== ② 起服务"
cd localservice/build/install/localservice || exit 1
java -cp "lib/*" com.gmssh.localservice.MainKt --port 8898 --data /tmp/gmssh-data > /tmp/gmsshlocal.log 2>&1 &
SVC=$!
sleep 6

echo "=== ③ 健康检查"
curl -s --max-time 8 http://127.0.0.1:8898/__health; echo

echo "=== ④ 真 SSH 测试（ed25519 私钥 → 127.0.0.1:22）"
curl -s --max-time 45 -X POST -H "Content-Type: application/json" -d @/tmp/test_node.json http://127.0.0.1:8898/node/test; echo

echo "=== ⑤ 反向用例（错误私钥）"
python3 - <<'PY'
import io, json
d = json.load(io.open("/tmp/test_node.json", encoding="utf-8"))
d["authValue"] = "-----BEGIN OPENSSH PRIVATE KEY-----\nAAAAbogusbogusbogus\n-----END OPENSSH PRIVATE KEY-----\n"
io.open("/tmp/test_node_bad.json", "w", encoding="utf-8").write(json.dumps(d))
PY
curl -s --max-time 45 -X POST -H "Content-Type: application/json" -d @/tmp/test_node_bad.json http://127.0.0.1:8898/node/test; echo

echo "=== ⑥ 服务日志（尾部）"
tail -12 /tmp/gmsshlocal.log

kill $SVC 2>/dev/null
echo "=== 完成"
