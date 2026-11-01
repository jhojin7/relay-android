#!/usr/bin/env python3
"""Drive only the dedicated disposable Relay emulator. No third-party packages."""

import argparse
import os
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[4]
SDK = Path(os.environ.get("ANDROID_HOME", Path.home() / "Library/Android/sdk"))
SERIAL = "emulator-5580"
AVD = "relay-evaluation-disposable"
EVIDENCE = ROOT / "build/verification"


def adb(*args, binary=False):
    return subprocess.check_output(
        [str(SDK / "platform-tools/adb"), "-s", SERIAL, *args],
        text=not binary,
        stderr=subprocess.STDOUT,
    )


def doctor():
    name = adb("emu", "avd", "name").splitlines()[0].strip()
    if name != AVD or adb("shell", "getprop", "ro.kernel.qemu").strip() != "1":
        raise SystemExit("Refusing to drive anything except the dedicated disposable AVD")
    if adb("shell", "getprop", "sys.boot_completed").strip() != "1":
        raise SystemExit("Emulator not ready")
    print(f"Healthy: {SERIAL}, {name}, boot complete", flush=True)


def hierarchy():
    adb("shell", "uiautomator", "dump", "/sdcard/relay-ui.xml")
    return adb("shell", "cat", "/sdcard/relay-ui.xml")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=["doctor", "install", "test", "launch", "ui", "click", "scroll", "back", "screenshot", "pull-evidence", "exports", "cleanup"])
    parser.add_argument("value", nargs="?")
    args = parser.parse_args()
    doctor()
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    if args.command == "install":
        for apk in ["app/build/outputs/apk/debug/app-debug.apk", "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"]:
            print(adb("install", "-r", str(ROOT / apk)))
        print(adb("shell", "pm", "grant", "com.hush.voice", "android.permission.RECORD_AUDIO"))
    elif args.command == "test":
        command = ["shell", "am", "instrument", "-r", "-w"]
        if args.value:
            command += ["-e", "class", args.value]
        result = adb(*command, "com.hush.voice.test/com.hush.voice.HushTestRunner")
        name = "ui-tests.txt" if args.value else "runtime-tests.txt"
        (EVIDENCE / name).write_text(result)
        print(result)
        if not re.search(r"INSTRUMENTATION_RESULT: failed=0\b", result):
            raise SystemExit("Instrumentation failed; inspect saved report")
    elif args.command == "launch":
        print(adb("shell", "am", "start", "-n", "com.hush.voice/.MainActivity"))
    elif args.command == "ui":
        xml = hierarchy()
        (EVIDENCE / "ui.xml").write_text(xml)
        for node in ET.fromstring(xml).iter("node"):
            if node.get("text") or node.get("content-desc"):
                print({k: node.get(k) for k in ["text", "content-desc", "bounds", "checked", "clickable"]})
    elif args.command == "click":
        if not args.value:
            raise SystemExit("click requires an exact text or content description")
        root = ET.fromstring(hierarchy())
        matches = [n for n in root.iter("node") if args.value.casefold() in (n.get("text", "").casefold(), n.get("content-desc", "").casefold())]
        clickable = [n for n in matches if n.get("clickable") == "true"]
        if len(clickable) == 1:
            matches = clickable
        if len(matches) != 1:
            raise SystemExit(f"Expected one visible match, got {len(matches)}")
        coordinates = list(map(int, re.findall(r"\d+", matches[0].get("bounds", ""))))
        if len(coordinates) != 4 or coordinates[2] <= coordinates[0] or coordinates[3] <= coordinates[1]:
            raise SystemExit("Target is not visible; scroll then inspect the UI")
        x1, y1, x2, y2 = coordinates
        print(adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2)))
    elif args.command == "scroll":
        y1, y2 = (1800, 650) if args.value != "up" else (650, 1800)
        print(adb("shell", "input", "swipe", "540", str(y1), "540", str(y2), "400"))
    elif args.command == "back":
        print(adb("shell", "input", "keyevent", "4"))
    elif args.command == "screenshot":
        if not args.value or not re.fullmatch(r"[a-z0-9-]+", args.value):
            raise SystemExit("Use a lowercase screenshot name")
        (EVIDENCE / (args.value + ".png")).write_bytes(adb("exec-out", "screencap", "-p", binary=True))
    elif args.command == "pull-evidence":
        names = adb("shell", "run-as", "com.hush.voice", "ls", "files/verification").splitlines()
        for name in names:
            if re.fullmatch(r"[a-z-]+\.png", name):
                (EVIDENCE / name).write_bytes(adb("exec-out", "run-as", "com.hush.voice", "cat", "files/verification/" + name, binary=True))
    elif args.command == "exports":
        names = adb("shell", "ls", "/sdcard/Download").splitlines()
        for name in names:
            if re.fullmatch(r"Relay-evaluation-[0-9-]+\.zip", name):
                print(adb("pull", "/sdcard/Download/" + name, str(EVIDENCE / name)))
    elif args.command == "cleanup":
        print(adb("shell", "am", "force-stop", "com.hush.voice"))
        print(adb("emu", "kill"))
        if not any(EVIDENCE.glob("*.png")):
            raise SystemExit("No screenshot evidence survived cleanup")
        print("Emulator stopped; proof retained at", EVIDENCE)


if __name__ == "__main__":
    main()
