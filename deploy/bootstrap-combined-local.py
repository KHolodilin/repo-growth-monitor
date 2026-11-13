"""Import the existing local development token through Admin APIs. Never prints secrets."""
from pathlib import Path
import http.cookiejar
import json
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

root = Path(__file__).resolve().parents[1]
config_path = root / "deploy/tmp/rgm-runtime.local.yml"
if not config_path.exists():
    raise SystemExit("Run python deploy/prepare-combined-local.py first (missing deploy/tmp/rgm-runtime.local.yml).")
config = json.loads(config_path.read_text())
container = json.loads(
    subprocess.check_output(["docker", "inspect", "repo-growth-monitor-repo-growth-monitor-1"], text=True)
)[0]
environment = dict(item.split("=", 1) for item in container["Config"]["Env"])
if environment.get("AUTH_PROVIDER", "mock") != "mock" or environment.get("AUTH_PRODUCTION", "false").lower() != "false":
    raise SystemExit("This bootstrap supports only the local non-production MOCK stack.")
token = environment.get("GITHUB_TOKEN", "")
if not token:
    raise SystemExit("Configure GITHUB_TOKEN in the local .env before bootstrapping.")
base = "http://127.0.0.1:8080"
cookies = http.cookiejar.CookieJar()
client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookies))


def csrf():
    return next(urllib.parse.unquote(cookie.value) for cookie in cookies if cookie.name == "XSRF-TOKEN")


def api(path, method="GET", value=None):
    headers = {}
    data = None
    if method != "GET":
        headers["X-XSRF-TOKEN"] = csrf()
    if value is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(value).encode()
    try:
        with client.open(
            urllib.request.Request(base + path, data=data, headers=headers, method=method), timeout=20
        ) as response:
            body = response.read()
            return json.loads(body) if body else None
    except urllib.error.HTTPError as error:
        raise SystemExit(f"Local API failed: {method} {path}: HTTP {error.code}") from None


client.open(base + "/login", timeout=20).read()
client.open(
    urllib.request.Request(
        base + "/login",
        data=urllib.parse.urlencode({"username": "admin", "password": "admin", "_csrf": csrf()}).encode(),
        method="POST",
    ),
    timeout=20,
).read()
if "ADMIN" not in api("/api/v1/me")["roles"]:
    raise SystemExit("Local MOCK admin authentication failed.")
ref = config["rgm"]["credentials"]["default-ref"]
# Inspect only existence, never ciphertext or plaintext. Existing credentials are not silently rotated.
exists = subprocess.check_output(
    [
        "docker",
        "compose",
        "exec",
        "-T",
        "postgres",
        "psql",
        "-U",
        "postgres",
        "-d",
        "repogrowth",
        "-Atc",
        "SELECT count(*) FROM github_credential WHERE ref='" + ref.replace("'", "''") + "'",
    ],
    cwd=root,
    text=True,
).strip()
if exists == "0" or "--rotate" in sys.argv:
    api("/api/v1/admin/credentials/" + urllib.parse.quote(ref, safe=""), "PUT", {"token": token})
del token, environment
config["rgm"]["planner"]["enabled"] = True
config_path.write_text(json.dumps(config, indent=2) + "\n")
print("Local token configured in the encrypted vault; unassigned selected repositories use its default reference.")
print("Restart both app services to enable the planner against the collector container:")
print("  docker compose up -d --force-recreate repo-growth-monitor collector")
