#!/usr/bin/env python3
"""Merge the delta between two official Codex commits into the local snapshot."""
import argparse
import os
from pathlib import Path
import re
import subprocess
import sys

PREFIX = 'upstream/codex'
MARKER = 'CODEX_UPSTREAM_COMMIT'
ORIGIN = 'https://github.com/openai/codex'
SHA = re.compile(r'[0-9a-f]{40}')


class UpgradeError(RuntimeError):
    pass


def git(repo, *args, data=None, check=True):
    env = dict(os.environ, GIT_AUTHOR_NAME='Codex upstream merge',
               GIT_AUTHOR_EMAIL='codex-upstream@example.invalid',
               GIT_COMMITTER_NAME='Codex upstream merge',
               GIT_COMMITTER_EMAIL='codex-upstream@example.invalid')
    result = subprocess.run(['git', '-C', str(repo), *args], input=data,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env)
    if check and result.returncode:
        raise UpgradeError(f"git {' '.join(args)}: {result.stderr.decode(errors='replace').strip()}")
    return result


def output(repo, *args):
    return git(repo, *args).stdout.decode().strip()


def merge(repo, target):
    repo = Path(repo).resolve()
    if Path(output(repo, 'rev-parse', '--show-toplevel')).resolve() != repo:
        raise UpgradeError('必须在 111 仓库根目录运行')
    if output(repo, 'status', '--porcelain', '--untracked-files=all'):
        raise UpgradeError('工作区存在未提交变更，先提交或保存后再升级')
    marker = repo / MARKER
    lines = marker.read_text(encoding='utf-8').splitlines()
    if len(lines) != 3 or not SHA.fullmatch(lines[0]) or lines[2] != ORIGIN:
        raise UpgradeError('当前官方基线记录无效')
    old = lines[0]
    if not SHA.fullmatch(target):
        raise UpgradeError('候选必须为完整官方提交 SHA')
    for sha in (old, target):
        if output(repo, 'rev-parse', '--verify', sha + '^{commit}') != sha:
            raise UpgradeError('官方提交对象不匹配')
    if old == target:
        print('官方基线未变化')
        return
    if git(repo, 'merge-base', '--is-ancestor', old, target, check=False).returncode:
        raise UpgradeError('候选未继承当前官方基线；需独立审计分叉或降级')
    base_tree = output(repo, 'rev-parse', old + '^{tree}')
    next_tree = output(repo, 'rev-parse', target + '^{tree}')
    local_tree = output(repo, 'rev-parse', 'HEAD:' + PREFIX)
    # Synthetic commits give Git precisely the three trees and common ancestor.
    # Nothing is checked out or staged until a conflict-free result exists.
    base = output(repo, 'commit-tree', base_tree, '-m', 'official base')
    ours = output(repo, 'commit-tree', local_tree, '-p', base, '-m', '111 snapshot')
    theirs = output(repo, 'commit-tree', next_tree, '-p', base, '-m', 'official target')
    result = git(repo, 'merge-tree', '--write-tree', ours, theirs, check=False)
    if result.returncode == 1:
        raise UpgradeError('三方合并未通过，原基线和工作区未改动：\n' +
                           result.stdout.decode(errors='replace') + result.stderr.decode(errors='replace'))
    if result.returncode:
        raise UpgradeError('Git 三方合并执行失败：' + result.stderr.decode(errors='replace'))
    merged = result.stdout.decode().splitlines()[0]
    if not SHA.fullmatch(merged) or output(repo, 'cat-file', '-t', merged) != 'tree':
        raise UpgradeError('合并结果无效')
    print('官方变更：\n' + output(repo, 'diff', '--stat', base_tree, next_tree))
    print('实际增量：\n' + output(repo, 'diff', '--stat', local_tree, merged))
    patch = git(repo, 'diff', '--binary', '--full-index', '--no-renames',
                '--no-ext-diff', '--no-textconv', '--src-prefix=a/' + PREFIX + '/',
                '--dst-prefix=b/' + PREFIX + '/', local_tree, merged).stdout
    if patch:
        git(repo, 'apply', '--check', '--index', '--whitespace=nowarn', data=patch)
        git(repo, 'apply', '--index', '--whitespace=nowarn', data=patch)
    index_tree = output(repo, 'write-tree')
    if output(repo, 'rev-parse', index_tree + ':' + PREFIX) != merged:
        raise UpgradeError('实际暂存源码与三方合并树不一致，停止发布')
    marker.write_text(target + '\n' + output(repo, 'show', '-s', '--format=%cI', target) +
                      '\n' + ORIGIN + '\n', encoding='utf-8')
    git(repo, 'add', '--', MARKER)
    print(f'增量合并通过：{old} -> {target}；合并树 {merged}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', type=Path, default=Path.cwd())
    parser.add_argument('--target', required=True)
    args = parser.parse_args()
    try:
        merge(args.repo, args.target)
    except (UpgradeError, OSError, ValueError) as error:
        print(f'上游升级失败：{error}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
