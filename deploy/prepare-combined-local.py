"""Provision the local split RGM + Collector topology (prod-like two containers)."""
from pathlib import Path
import json, secrets, base64
import subprocess, sys

r = Path(__file__).resolve().parents[1]
private = r / "deploy/tmp"
private.mkdir(parents=True, exist_ok=True)
rgm_config = private / "rgm-runtime.local.yml"
collector_config = private / "collector-runtime.local.yml"
legacy = private / "combined-runtime.local.yml"

RGM = "http://repo-growth-monitor:8080"
COLLECTOR = "http://collector:8080"
NODE = "local-collector"


def load_legacy_secrets():
    """Reuse keys/tokens from the previous one-JVM combined file when present."""
    if not legacy.exists():
        return None
    settings = json.loads(legacy.read_text())
    password = settings["collector"]["datasource"]["password"]
    node = settings["rgm"]["orchestration"]["nodes"][0]
    accept = node["service-token"]
    resolve = node["resolve-token"]
    encryption = settings["rgm"]["credentials"]["encryption-keys"]
    key_id = settings["rgm"]["credentials"]["active-key-id"]
    default_ref = settings["rgm"]["credentials"]["default-ref"]
    return password, accept, resolve, encryption, key_id, default_ref


def load_split_secrets():
    if not (rgm_config.exists() and collector_config.exists()):
        return None
    rgm = json.loads(rgm_config.read_text())
    collector = json.loads(collector_config.read_text())
    if collector["collector"]["datasource"]["url"] != "jdbc:postgresql://postgres:5432/collector":
        sys.exit("Migrate the existing Collector database before changing its datasource.")
    password = collector["collector"]["datasource"]["password"]
    node = rgm["rgm"]["orchestration"]["nodes"][0]
    accept = node["service-token"]
    resolve = node["resolve-token"]
    encryption = rgm["rgm"]["credentials"]["encryption-keys"]
    key_id = rgm["rgm"]["credentials"]["active-key-id"]
    default_ref = rgm["rgm"]["credentials"]["default-ref"]
    return password, accept, resolve, encryption, key_id, default_ref


existing = load_split_secrets() or load_legacy_secrets()
if existing:
    password, accept, resolve, encryption, key_id, default_ref = existing
else:
    password = secrets.token_urlsafe(32)
    accept = secrets.token_urlsafe(40)
    resolve = secrets.token_urlsafe(40)
    key_id = "local-v1"
    encryption = {key_id: base64.b64encode(secrets.token_bytes(32)).decode()}
    default_ref = "local-existing-token"

rgm_settings = {
    "rgm": {
        "collector": {"enabled": False},
        "planner": {"enabled": False},
        "credentials": {
            "enabled": True,
            "default-ref": default_ref,
            "active-key-id": key_id,
            "encryption-keys": encryption,
            "cache-lease": "2h",
        },
        "orchestration": {
            "enabled": True,
            "allow-http": True,
            "nodes": [
                {
                    "key": NODE,
                    "pool": "default",
                    "endpoint": COLLECTOR,
                    "enabled": True,
                    "service-token": accept,
                    "resolve-token": resolve,
                }
            ],
        },
    }
}

collector_settings = {
    "collector": {
        "storage": {"enabled": True},
        "datasource": {
            "url": "jdbc:postgresql://postgres:5432/collector",
            "username": "collector",
            "password": password,
        },
        "http": {
            "enabled": True,
            "clients": [{"origin": "rgm", "token": accept}],
        },
        "credentials": {
            "enabled": True,
            "ttl": "2h",
            "maximum-size": 100,
            "allow-http": True,
            "origins": [
                {
                    "origin": "rgm",
                    "endpoint": RGM,
                    "service-token": resolve,
                }
            ],
        },
        "execution": {"enabled": True},
        "delivery": {
            "enabled": True,
            "allow-http": True,
            "destinations": [
                {
                    "ref": "rgm-results",
                    "origin": "rgm",
                    "endpoint": f"{RGM}/internal/v1/collector-results",
                }
            ],
        },
    }
}

rgm_config.write_text(json.dumps(rgm_settings, indent=2) + "\n")
collector_config.write_text(json.dumps(collector_settings, indent=2) + "\n")

# Provision on every preparation, including stacks with an existing product volume.
subprocess.run(["docker", "compose", "up", "-d", "--wait", "postgres"], cwd=r, check=True)
psql = [
    "docker",
    "compose",
    "exec",
    "-T",
    "postgres",
    "psql",
    "-XAt",
    "-v",
    "ON_ERROR_STOP=1",
    "-U",
    "postgres",
    "-d",
    "postgres",
]


def sql(statement):
    return subprocess.run(
        psql, input=statement, text=True, cwd=r, check=True, capture_output=True
    ).stdout.strip()


if not sql("SELECT 1 FROM pg_roles WHERE rolname='collector'"):
    sql("CREATE ROLE collector LOGIN PASSWORD '" + password.replace("'", "''") + "'")
else:
    sql("ALTER ROLE collector PASSWORD '" + password.replace("'", "''") + "'")
owner = sql("SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname='collector'")
if not owner:
    sql("CREATE DATABASE collector OWNER collector")
elif owner != "collector":
    sys.exit("Existing collector database has a different owner.")
print("Collector database ready. Split RGM/Collector runtime files written under deploy/tmp/.")
print("Start stack: docker compose up -d --build")
print("Then: python deploy/bootstrap-combined-local.py")
