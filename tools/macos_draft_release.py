#!/usr/bin/env python3
"""公証版を同じコミットの Draft Release にだけ添付する。gh は env から認証。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile


def gh(*arguments):
    return subprocess.check_output(['gh', *arguments], text=True)


def check_target(tag, sha):
    if tag.startswith('-') or subprocess.run(
            ['git', 'check-ref-format', 'refs/tags/' + tag],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode:
        raise ValueError('Invalid release tag')
    if not re.fullmatch(r'[0-9a-f]{40}', sha):
        raise ValueError('Expected the full build commit SHA')
    tag_commit = subprocess.run(
        ['git', 'rev-parse', '--verify', 'refs/tags/' + tag + '^{commit}'],
        text=True, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    if tag_commit.returncode == 0 and tag_commit.stdout.strip() != sha:
        raise ValueError('Existing tag points to a different commit; choose a new tag')
    pages = json.loads(gh('api', 'repos/{owner}/{repo}/releases?per_page=100',
                          '--paginate', '--slurp'))
    release = next((item for page in pages for item in page if item['tag_name'] == tag), None)
    if release:
        if not release['draft']:
            raise ValueError('Release is already published; it will not be changed')
        # 再実行は本ワークフローが作成した同じ SHA の Draft だけを更新する。
        if release['target_commitish'] != sha:
            raise ValueError('Existing draft targets a different commit; choose a new tag')
    return release


def checked_assets(directory):
    assets = []
    for arch in ('arm64', 'x86_64'):
        name = f'mxv2-macos-{arch}-notarized.zip'
        archive = directory / name
        checksum = directory / (name + '.sha256')
        if not archive.is_file() or not checksum.is_file():
            raise ValueError('Both architectures and their checksums are required')
        fields = checksum.read_text().strip().split()
        digest = hashlib.sha256(archive.read_bytes()).hexdigest()
        if fields != [digest, name]:
            raise ValueError('Checksum mismatch: ' + name)
        assets.extend((str(archive), str(checksum)))
    return assets


def create_draft(tag, sha, directory):
    # 全成果物の検証を終えてから GitHub を変更する。
    assets = checked_assets(directory)
    release = check_target(tag, sha)
    if not release:
        notes = (
            f'macOS の Developer ID 署名・公証済みビルド。\n\n'
            f'ソースコミット: `{sha}`\n\n'
            '- Apple Silicon: `mxv2-macos-arm64-notarized.zip`\n'
            '- Intel: `mxv2-macos-x86_64-notarized.zip`\n\n'
            'macOS 11 以降。公証チケットをアプリに添付し、署名・公証・'
            'Gatekeeper の検証を通過したものです。SHA256 ファイルも添付しています。\n\n'
            'Draft のため、内容を確認してから手動で公開してください。\n'
        )
        with tempfile.TemporaryDirectory(prefix='mxv2-release-') as temporary:
            body = Path(temporary) / 'notes.md'
            body.write_text(notes, encoding='utf-8')
            print(gh('release', 'create', tag, '--draft', '--target', sha,
                     '--title', 'mxv2 macOS ' + tag, '--notes-file', str(body)).strip())
    # 新規作成した場合も Draft のままか再確認してから添付する。
    release = check_target(tag, sha)
    if not release:
        raise ValueError('Draft Release was not found after creation')
    gh('release', 'upload', tag, *assets, '--clobber')
    print('Updated Draft Release: ' + release['html_url'])
    summary = os.environ.get('GITHUB_STEP_SUMMARY')
    if summary:
        with open(summary, 'a', encoding='utf-8') as stream:
            stream.write(f'Draft Release: {release["html_url"]}\n\nCommit: `{sha}`\n')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--tag', required=True)
    parser.add_argument('--sha', required=True)
    parser.add_argument('--check-only', action='store_true')
    parser.add_argument('--assets', type=Path)
    args = parser.parse_args()
    try:
        if args.check_only:
            check_target(args.tag, args.sha)
            print('Release tag and draft target are valid')
        elif args.assets:
            create_draft(args.tag, args.sha, args.assets)
        else:
            parser.error('--assets is required when creating a draft')
    except (ValueError, subprocess.CalledProcessError) as error:
        raise SystemExit(str(error)) from None


if __name__ == '__main__':
    main()
