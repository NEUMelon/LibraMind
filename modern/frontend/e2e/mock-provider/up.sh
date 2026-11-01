#!/usr/bin/env bash
# 仅限云端测试环境：把 aihubmix.com 指向本机的模拟服务，并以“假 Key”重启后端。
# 真实 Key 不会发给模拟服务。结束后运行 down.sh 恢复 hosts 并按正常方式重启后端。
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
BACKEND="${LIBRAMIND_BACKEND:-$(cd "$HERE/../../../backend" && pwd)}"
C="$HERE/certs"
mkdir -p "$C"
if [ ! -f "$C/server.crt" ]; then
  openssl req -x509 -newkey rsa:2048 -nodes -days 30 -subj "/CN=LibraMind Mock CA" -keyout "$C/ca.key" -out "$C/ca.crt" 2>/dev/null
  openssl req -newkey rsa:2048 -nodes -subj "/CN=aihubmix.com" -keyout "$C/server.key" -out "$C/server.csr" 2>/dev/null
  printf "subjectAltName=DNS:aihubmix.com\nbasicConstraints=CA:FALSE\nextendedKeyUsage=serverAuth\n" > "$C/ext.cnf"
  openssl x509 -req -in "$C/server.csr" -CA "$C/ca.crt" -CAkey "$C/ca.key" -CAcreateserial -days 30 -extfile "$C/ext.cnf" -out "$C/server.crt" 2>/dev/null
fi
if [ ! -f "$C/truststore.jks" ]; then
  keytool -importkeystore -srckeystore /etc/ssl/certs/java/cacerts -srcstorepass changeit -destkeystore "$C/truststore.jks" -deststorepass changeit -noprompt >/dev/null 2>&1
  keytool -importcert -alias libramind-mock -file "$C/ca.crt" -keystore "$C/truststore.jks" -storepass changeit -noprompt >/dev/null
fi
grep -q "aihubmix.com # libramind-mock" /etc/hosts || echo "127.0.0.1 aihubmix.com # libramind-mock" >> /etc/hosts
if ! (exec 3<>/dev/tcp/127.0.0.1/443) 2>/dev/null; then
  nohup node "$HERE/server.mjs" > "$HERE/mock.log" 2>&1 &
  echo $! > "$HERE/mock.pid"
  sleep 1
fi
# 后端：去掉 aihubmix.com 的出站代理，信任模拟 CA，Key 用占位值
"$BACKEND/scripts/stop.sh" >/dev/null 2>&1 || true
for _ in $(seq 1 30); do (exec 3<>/dev/tcp/127.0.0.1/8090) 2>/dev/null || break; sleep 1; done
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Djavax.net.ssl.trustStore=$C/truststore.jks -Djavax.net.ssl.trustStorePassword=changeit -Dhttp.nonProxyHosts=localhost|127.*|aihubmix.com"
export AIHUBMIX_API_KEY="mock-local-not-a-real-key"
"$BACKEND/scripts/start.sh"
echo "模拟模型已接管 aihubmix.com（Mock，不是真实模型）。"
