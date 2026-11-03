#!/usr/bin/env python3
"""Validate a Relay release APK; transfer only with --send and a reviewed hash."""

import argparse
from datetime import datetime, timezone
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile


def run(args, cwd=None, timeout=30):
    result = subprocess.run([str(a) for a in args], cwd=cwd, text=True,
                            capture_output=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(f"{Path(str(args[0])).name} failed (exit {result.returncode}); private command output suppressed")
    return result.stdout


def common_dir(repo):
    return Path(run(["git", "rev-parse", "--path-format=absolute", "--git-common-dir"], cwd=repo).strip()).resolve()


def sdk_tools(repo, common):
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        for base in (repo, common.parent):
            props = base / "local.properties"
            if props.is_file():
                match = re.search(r"^sdk\.dir\s*=\s*(.+)$", props.read_text(), re.M)
                if match:
                    sdk = match[1].strip()
                    break
    if not sdk:
        raise RuntimeError("Android SDK not found; set ANDROID_HOME")
    candidates = [p for p in (Path(sdk) / "build-tools").glob("*")
                  if re.fullmatch(r"\d+\.\d+\.\d+", p.name)
                  and all((p / t).is_file() for t in ("apksigner", "aapt"))]
    if not candidates:
        raise RuntimeError("apksigner and aapt not found")
    return max(candidates, key=lambda p: tuple(map(int, p.name.split("."))))


def phone(cli, config):
    status = json.loads(run([cli, "status", "--json"]))
    if status.get("BackendState") != "Running":
        raise RuntimeError("Tailscale is not running")
    peers = [p for p in status.get("Peer", {}).values() if p.get("ID") == config["peer_id"]]
    if len(peers) != 1:
        raise RuntimeError("Configured phone not found uniquely; confirm device selection")
    peer = peers[0]
    if (peer.get("DNSName", "").rstrip(".") != config["dns_name"].rstrip(".")
            or peer.get("OS") != "android" or not peer.get("Online")
            or not peer.get("UserID") or peer["UserID"] != status.get("Self", {}).get("UserID")):
        raise RuntimeError("Phone identity, ownership or online check failed")
    ips = [str(ipaddress.ip_address(ip)) for ip in peer.get("TailscaleIPs", [])
           if ipaddress.ip_address(ip).version == 4]
    targets = {line.split()[0] for line in run([cli, "file", "cp", "--targets"]).splitlines() if line.split()}
    if len(ips) != 1 or ips[0] not in targets:
        raise RuntimeError("Phone is not a Taildrop target")
    return {"name": peer["HostName"], "dns_name": peer["DNSName"].rstrip("."), "ip": ips[0], "peer_id": peer["ID"]}


def inspect_apk(apk, tools, expected_cert):
    certs = re.findall(r"^(?:Signer #\d+|V\d+ Signer):? certificate SHA-256 digest: ([0-9a-fA-F]+)$",
                       run([tools / "apksigner", "verify", "--print-certs", apk]), re.M)
    if {c.lower() for c in certs} != {expected_cert.lower()}:
        raise RuntimeError("APK does not have the configured release signing certificate")
    badging = run([tools / "aapt", "dump", "badging", apk])
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.M)
    if not package or package[1] != "com.hush.voice":
        raise RuntimeError("APK application ID is not com.hush.voice")
    if "application-debuggable" in badging or "testOnly='true'" in badging:
        raise RuntimeError("Debuggable or test APK cannot be used for release delivery")
    return {"application_id": package[1], "version_code": int(package[2]), "version_name": package[3]}


def public_receipt(receipt):
    """Keep local device identifiers and user paths out of shareable output."""
    fields = ("status", "mode", "source_revision", "source_dirty", "filename", "sha256",
              "size_bytes", "application_id", "version_code", "version_name", "completed_at")
    return {key: receipt[key] for key in fields if key in receipt}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--expected-sha256")
    parser.add_argument("--send", action="store_true", help="Transfer only for an explicit user send request")
    args = parser.parse_args()
    repo = args.repo.resolve()
    common = common_dir(repo)
    if common != common_dir(Path(__file__).resolve().parents[4]):
        raise RuntimeError("Source worktree is not part of this Relay repository")
    config = json.loads((common / "info/relay-taildrop.json").read_text())
    if not re.fullmatch(r"[0-9a-fA-F]{64}", config["certificate_sha256"]):
        raise RuntimeError("Invalid configured release certificate")
    apk = (repo / args.apk).resolve()
    if not apk.is_relative_to(repo) or apk.suffix != ".apk" or not apk.is_file():
        raise RuntimeError("APK must be a .apk file inside the selected worktree")
    if args.send and not args.expected_sha256:
        raise RuntimeError("--send requires --expected-sha256 from the inspected artifact")
    cli = shutil.which("tailscale")
    if not cli:
        raise RuntimeError("tailscale CLI not found")
    target = phone(cli, config)
    tools = sdk_tools(repo, common)
    staging = repo / "app/build/taildrop"
    staging.mkdir(parents=True, exist_ok=True)
    if subprocess.run(["git", "check-ignore", "-q", str(staging)], cwd=repo).returncode:
        raise RuntimeError("APK staging directory must be ignored by Git")
    with tempfile.TemporaryDirectory(prefix="transfer-", dir=staging) as temporary:
        snapshot = Path(temporary) / "snapshot.apk"
        shutil.copyfile(apk, snapshot)
        digest = hashlib.sha256(snapshot.read_bytes()).hexdigest()
        if args.expected_sha256 and digest != args.expected_sha256.lower():
            raise RuntimeError("APK SHA-256 changed; inspect the intended artifact again")
        metadata = inspect_apk(snapshot, tools, config["certificate_sha256"])
        version = re.sub(r"[^A-Za-z0-9._-]", "_", metadata["version_name"])
        filename = f"Relay-{version}-vc{metadata['version_code']}-release-{digest[:8]}.apk"
        payload = snapshot.with_name(filename)
        snapshot.rename(payload)
        receipt = {"mode": "send" if args.send else "check", "source_apk": str(apk),
                   "source_revision": run(["git", "rev-parse", "HEAD"], cwd=repo).strip(),
                   "source_dirty": bool(run(["git", "status", "--porcelain"], cwd=repo).strip()),
                   "filename": filename, "sha256": digest, "size_bytes": payload.stat().st_size,
                   "certificate_sha256": config["certificate_sha256"], **metadata, "target": target}
        if args.send:
            if phone(cli, config) != target:
                raise RuntimeError("Phone changed during validation; stop and inspect again")
            print(json.dumps(public_receipt({"status": "transferring", **receipt}), ensure_ascii=False), flush=True)
            run([cli, "file", "cp", payload, target["ip"] + ":"], timeout=120)
            receipt["status"] = "taildrop_command_completed"
            receipt["completed_at"] = datetime.now(timezone.utc).isoformat()
            local_receipt = staging / "last-transfer.json"
            local_receipt.touch(mode=0o600, exist_ok=True)
            local_receipt.chmod(0o600)
            local_receipt.write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n")
        else:
            receipt["status"] = "validated_no_transfer"
        print(json.dumps(public_receipt(receipt), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    try:
        main()
    except subprocess.TimeoutExpired:
        sys.exit("Command timed out; transfer outcome may be unknown. Check phone before retrying.")
    except RuntimeError as error:
        sys.exit(f"Stopped: {error}")
    except (OSError, ValueError, KeyError):
        sys.exit("Stopped: invalid or unavailable local input/configuration; private details suppressed")
