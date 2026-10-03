#!/usr/bin/env python3
"""秘密鍵や実際の Apple/GitHub API を使わず、公開防止と失敗時の後始末を検証。"""
import base64
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import macos_draft_release as release

SHA = 'a' * 40
TAG = 'macos-test'
TOOLS = Path(__file__).resolve().parent


class DraftReleaseTests(unittest.TestCase):
    def assets(self, root):
        for arch in ('arm64', 'x86_64'):
            name = f'mxv2-macos-{arch}-notarized.zip'
            data = arch.encode()
            (root / name).write_bytes(data)
            (root / (name + '.sha256')).write_text(hashlib.sha256(data).hexdigest() + '  ' + name + '\n')

    def target(self, item, tag_sha=None):
        responses = [subprocess.CompletedProcess([], 0),
                     subprocess.CompletedProcess([], 0 if tag_sha else 1, stdout=(tag_sha or '') + '\n')]
        with patch.object(release.subprocess, 'run', side_effect=responses), \
                patch.object(release, 'gh', return_value=json.dumps([[item]])):
            return release.check_target(TAG, SHA)

    def test_published_release_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'already published'):
            self.target(dict(tag_name=TAG, draft=False, target_commitish=SHA))

    def test_different_draft_commit_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'different commit'):
            self.target(dict(tag_name=TAG, draft=True, target_commitish='b' * 40))

    def test_different_tag_commit_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'Existing tag'):
            self.target(dict(tag_name=TAG, draft=True, target_commitish=SHA), 'b' * 40)

    def test_existing_matching_draft_is_allowed(self):
        item = dict(tag_name=TAG, draft=True, target_commitish=SHA)
        self.assertEqual(self.target(item, SHA), item)

    def test_missing_architecture_prevents_any_github_call(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(release, 'gh') as gh:
            with self.assertRaisesRegex(ValueError, 'Both architectures'):
                release.create_draft(TAG, SHA, Path(temporary))
            gh.assert_not_called()

    def test_tampered_package_prevents_any_github_call(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(release, 'gh') as gh:
            root = Path(temporary)
            self.assets(root)
            (root / 'mxv2-macos-arm64-notarized.zip').write_bytes(b'tampered')
            with self.assertRaisesRegex(ValueError, 'Checksum mismatch'):
                release.create_draft(TAG, SHA, root)
            gh.assert_not_called()

    def test_new_release_is_created_as_draft_at_exact_commit(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.assets(root)
            item = dict(html_url='https://example.test/draft', draft=True, target_commitish=SHA)
            with patch.object(release, 'check_target', side_effect=[None, item]), \
                    patch.object(release, 'gh', return_value='') as gh:
                release.create_draft(TAG, SHA, root)
            create, upload = [call.args for call in gh.call_args_list]
            self.assertIn('--draft', create)
            self.assertEqual(create[create.index('--target') + 1], SHA)
            self.assertEqual(upload[:3], ('release', 'upload', TAG))
            self.assertEqual(len(upload[3:-1]), 4)

    def test_draft_published_during_run_is_rejected_before_upload(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.assets(root)
            with patch.object(release, 'check_target', side_effect=[None, ValueError('already published')]), \
                    patch.object(release, 'gh', return_value='') as gh:
                with self.assertRaises(ValueError):
                    release.create_draft(TAG, SHA, root)
            self.assertEqual(gh.call_count, 1)


# 外部コマンドを差し替えて実際のシェルを実行する。秘密情報は合成値のみ。
MOCK_COMMAND = r'''#!/usr/bin/env python3
import json, os, sys, zipfile
from pathlib import Path
command = Path(sys.argv[0]).name
args = sys.argv[1:]
with open(os.environ['MOCK_CALLS'], 'a') as out:
    out.write(command + ' ' + args[0] + '\n')
if command == 'security':
    if args[0] == 'create-keychain': Path(args[-1]).touch()
    elif args[0] == 'delete-keychain': Path(args[-1]).unlink(missing_ok=True)
    elif args[0] == 'find-identity':
        print('1) ' + 'A' * 40 + ' "Developer ID Application: CI Test (ABCDEFGHIJ)"')
elif command == 'curl':
    Path(args[args.index('--output') + 1]).write_bytes(b'public test certificate')
elif command == 'xcrun':
    if args[:2] == ['notarytool', 'submit']:
        print(json.dumps({'id': '11111111-2222-3333-4444-555555555555',
                          'status': os.environ.get('MOCK_STATUS', 'Accepted')}))
        sys.exit(int(os.environ.get('MOCK_SUBMIT_EXIT', '0')))
    elif args[:2] == ['notarytool', 'log']:
        Path(args[3]).write_text('{"issues": ["synthetic failure"]}')
    elif args[:2] == ['stapler', 'staple']:
        Path(args[2], 'ticket').touch()
    elif args[:2] == ['stapler', 'validate']:
        if not Path(args[2], 'ticket').exists(): sys.exit(1)
elif command == 'ditto':
    app, output = Path(args[-2]), Path(args[-1])
    with zipfile.ZipFile(output, 'w') as archive:
        for file in app.rglob('*'):
            if file.is_file(): archive.write(file, file.relative_to(app.parent))
elif command == 'spctl':
    sys.exit(int(os.environ.get('MOCK_SPCTL_EXIT', '0')))
'''


class NotarizationTests(unittest.TestCase):
    def check_flow(self, status='Accepted', submit_exit=0, spctl_exit=0):
        with tempfile.TemporaryDirectory(prefix='mxv2-notary-test-') as temporary:
            root = Path(temporary)
            app = root / 'Test app.app'
            (app / 'Contents/MacOS').mkdir(parents=True)
            (app / 'Contents/MacOS/mxv2').write_bytes(b'test executable')
            mock_bin = root / 'bin'
            mock_bin.mkdir()
            mock = mock_bin / 'mock-command'
            # 現在の Python を使い、実機と CI の双方で同じモックを実行。
            mock.write_text(MOCK_COMMAND.replace('#!/usr/bin/env python3', '#!' + sys.executable, 1))
            mock.chmod(0o700)
            for name in ('security', 'curl', 'codesign', 'xcrun', 'ditto', 'spctl'):
                (mock_bin / name).symlink_to(mock)
            env = dict(os.environ, PATH=str(mock_bin) + os.pathsep + os.environ['PATH'],
                       RUNNER_TEMP=str(root), MOCK_CALLS=str(root / 'calls'),
                       MACOS_CERTIFICATE_BASE64=base64.b64encode(b'test p12').decode(),
                       MACOS_CERTIFICATE_PASSWORD='synthetic password', APPLE_ID='ci@example.test',
                       APPLE_APP_SPECIFIC_PASSWORD='synthetic app password', APPLE_TEAM_ID='ABCDEFGHIJ',
                       MOCK_STATUS=status, MOCK_SUBMIT_EXIT=str(submit_exit), MOCK_SPCTL_EXIT=str(spctl_exit))
            output = root / 'output'
            result = subprocess.run(['bash', str(TOOLS / 'notarize_macos.sh'), str(app), 'arm64', str(output)],
                                    env=env, text=True, stdout=subprocess.PIPE,
                                    stderr=subprocess.STDOUT, timeout=15)
            self.assertEqual(list(root.glob('mxv2-signing.*')), [], 'Signing material must be removed')
            archive = output / 'mxv2-macos-arm64-notarized.zip'
            calls = (root / 'calls').read_text()
            if status == 'Accepted' and submit_exit == 0 and spctl_exit == 0:
                self.assertEqual(result.returncode, 0, result.stdout)
                with zipfile.ZipFile(archive) as zipped:
                    self.assertIn('Test app.app/ticket', zipped.namelist())
                self.assertTrue(archive.with_name(archive.name + '.sha256').is_file())
                self.assertIn('spctl --assess', calls)
            else:
                self.assertNotEqual(result.returncode, 0, result.stdout)
                self.assertFalse(archive.exists(), 'Failed verification must not produce release assets')
                if status != 'Accepted' or submit_exit:
                    self.assertTrue((output / 'notary-log-arm64.json').is_file())
                    self.assertNotIn('xcrun stapler', calls)

    def test_success_packages_stapled_app_and_removes_signing_material(self):
        self.check_flow()

    def test_rejection_does_not_package_app_and_removes_signing_material(self):
        self.check_flow(status='Invalid')

    def test_timeout_does_not_package_app_and_removes_signing_material(self):
        self.check_flow(status='In Progress', submit_exit=1)

    def test_gatekeeper_failure_prevents_package(self):
        self.check_flow(spctl_exit=1)


if __name__ == '__main__':
    unittest.main()
