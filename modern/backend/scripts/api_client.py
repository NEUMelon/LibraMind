"""本地验收脚本共用的Session/CSRF客户端；凭据仅从私有文件读取。"""
import http.cookiejar
import json
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def properties(path):
    result = {}
    for line in Path(path).read_text(encoding="utf-8-sig").splitlines():
        if line.strip() and not line.lstrip().startswith(("#", "!")) and "=" in line:
            key, value = line.split("=", 1)
            result[key.strip()] = value.strip()
    return result


class Client:
    def __init__(self, base="http://127.0.0.1:8090"):
        if not base.startswith(("http://127.0.0.1:", "http://localhost:")):
            raise ValueError("Local acceptance client only")
        self.base = base.rstrip("/")
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        self.csrf = ""

    def request(self, method, path, body=None, key=None, raw=None, content_type=None):
        headers = {}
        if method not in ("GET", "HEAD") and self.csrf:
            headers["X-CSRF-TOKEN"] = self.csrf
        if key:
            headers["Idempotency-Key"] = key
        if body is not None:
            raw = json.dumps(body, ensure_ascii=False).encode("utf-8")
            content_type = "application/json"
        if content_type:
            headers["Content-Type"] = content_type
        request = urllib.request.Request(self.base + path, data=raw, headers=headers, method=method)
        try:
            with self.opener.open(request, timeout=180) as response:
                value = json.load(response)
        except urllib.error.HTTPError as failure:
            try:
                error = json.load(failure)
            except (ValueError, UnicodeError):
                error = {}
            raise RuntimeError(f"HTTP {failure.code}: {error.get('code', 'REQUEST_FAILED')} {error.get('message', '')}") from None
        if not value.get("success", False):
            raise RuntimeError(value.get("code", "REQUEST_FAILED"))
        return value.get("data")

    def login(self, username=None, password=None):
        if username is None:
            config = properties(ROOT / "config/application-local.properties")
            username = config.get("app.bootstrap.username", "admin")
            password = config["app.bootstrap.password"]
        self.csrf = self.request("GET", "/api/v1/auth/csrf")["token"]
        user = self.request("POST", "/api/v1/auth/login", dict(username=username, password=password))
        self.csrf = self.request("GET", "/api/v1/auth/csrf")["token"]
        return user

    def upload(self, path, image):
        boundary = "LibraMindLocalUploadBoundary"
        payload = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="cover.jpg"\r\n'
                   'Content-Type: image/jpeg\r\n\r\n').encode("ascii")
        payload += Path(image).read_bytes() + f"\r\n--{boundary}--\r\n".encode("ascii")
        return self.request("POST", path, raw=payload, content_type=f"multipart/form-data; boundary={boundary}")
