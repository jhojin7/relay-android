"""Privacy regressions; no live device commands or transfers."""

import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("send_apk", Path(__file__).with_name("send_apk.py"))
delivery = importlib.util.module_from_spec(spec)
spec.loader.exec_module(delivery)


class PrivacyTests(unittest.TestCase):
    def test_check_and_send_keep_device_details_local(self):
        for send in (False, True):
            with self.subTest(send=send), tempfile.TemporaryDirectory() as directory:
                repo = Path(directory)
                common = repo / ".git"
                (common / "info").mkdir(parents=True)
                cert = "a" * 64
                (common / "info/relay-taildrop.json").write_text(json.dumps({
                    "peer_id": "private-test-node", "dns_name": "private.example.invalid",
                    "certificate_sha256": cert,
                }))
                apk = repo / "input.apk"
                apk.write_bytes(b"test artifact")
                digest = hashlib.sha256(apk.read_bytes()).hexdigest()
                target = {"name": "private-test-phone", "peer_id": "private-test-node",
                          "dns_name": "private.example.invalid", "ip": "192.0.2.1"}
                calls = []

                def command(args, **kwargs):
                    calls.append(args)
                    return "" if "status" in args else "test-revision\n"

                args = ["send_apk.py", "--repo", str(repo), "--apk", str(apk),
                        "--expected-sha256", digest] + (["--send"] if send else [])
                output = io.StringIO()
                with patch.object(sys, "argv", args), \
                        patch.object(delivery, "common_dir", return_value=common), \
                        patch.object(delivery, "phone", return_value=target), \
                        patch.object(delivery, "sdk_tools", return_value=repo), \
                        patch.object(delivery, "inspect_apk", return_value={
                            "application_id": "com.hush.voice", "version_code": 1,
                            "version_name": "test",
                        }), \
                        patch.object(delivery, "run", side_effect=command), \
                        patch.object(delivery.shutil, "which", return_value="tailscale"), \
                        patch.object(delivery.subprocess, "run", return_value=subprocess.CompletedProcess([], 0)), \
                        contextlib.redirect_stdout(output):
                    delivery.main()
                console = output.getvalue()
                for private in [str(repo), cert, *target.values()]:
                    self.assertNotIn(private, console)
                self.assertIn(digest, console)
                transfers = [call for call in calls if call[:3] == ["tailscale", "file", "cp"]]
                self.assertEqual(len(transfers), int(send))
                receipt = repo / "app/build/taildrop/last-transfer.json"
                self.assertEqual(receipt.exists(), send)
                if send:
                    self.assertEqual(json.loads(receipt.read_text())["target"], target)
                    self.assertEqual(receipt.stat().st_mode & 0o777, 0o600)

    def test_command_failure_does_not_echo_private_output(self):
        failure = subprocess.CompletedProcess([], 1, "private-test-phone", "private.example.invalid")
        with patch.object(delivery.subprocess, "run", return_value=failure):
            with self.assertRaises(RuntimeError) as raised:
                delivery.run(["tailscale", "status"])
        self.assertNotIn("private-test-phone", str(raised.exception))
        self.assertNotIn("private.example.invalid", str(raised.exception))


if __name__ == "__main__":
    unittest.main()
