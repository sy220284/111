#!/usr/bin/env python3
"""Observe real build activity, size parallelism and terminate stalled process groups."""
import argparse
from datetime import datetime, timezone
import os
from pathlib import Path
import re
import selectors
import shutil
import signal
import subprocess
import sys
import time

MIB = 1024 ** 2
PROC = Path('/proc')
CGROUP = Path('/sys/fs/cgroup')
NINJA = re.compile(rb'(?:\[v8 [^\]]+\]\s+|^)\[(\d+)/(\d+)\]', re.M)


def cgroup_dirs():
    relative = next((line.split(':', 2)[2] for line in (PROC / 'self/cgroup').read_text().splitlines()
                     if line.startswith('0::')), '/')
    current = CGROUP / relative.lstrip('/')
    while current.is_relative_to(CGROUP):
        if current.is_dir():
            yield current
        if current == CGROUP:
            break
        current = current.parent


def available_resources():
    cpus = len(os.sched_getaffinity(0))
    memory = int(re.search(r'^MemAvailable:\s+(\d+)', (PROC / 'meminfo').read_text(), re.M)[1]) * 1024
    for directory in cgroup_dirs():
        cpu_file = directory / 'cpu.max'
        if cpu_file.exists():
            quota, period = cpu_file.read_text().split()
            if quota != 'max':
                cpus = min(cpus, max(1, int(quota) // int(period)))
        limit, used = directory / 'memory.max', directory / 'memory.current'
        if limit.exists() and used.exists():
            value = limit.read_text().strip()
            if value != 'max':
                memory = min(memory, max(0, int(value) - int(used.read_text())))
    return cpus, memory // MIB


def choose_jobs(profile, cpus, available_mib):
    # Leave 2 GiB for the OS, linker and other build processes. Conservative
    # per-worker memory estimates; larger runners can use additional cores.
    per_worker = {'v8': 2048, 'rust': 3072}[profile]
    return max(1, min(cpus, max(0, available_mib - 2048) // per_worker))


def group_sample(pgid):
    """Per-process CPU ticks and RSS, scoped to our process group, not the host."""
    ticks, rss = {}, 0
    for entry in PROC.iterdir():
        if not entry.name.isdecimal():
            continue
        try:
            stat = (entry / 'stat').read_text().rsplit(')', 1)[1].split()
            if int(stat[2]) != pgid or stat[0] == 'Z':
                continue
            ticks[(entry.name, stat[19])] = int(stat[11]) + int(stat[12])
            rss += int(stat[21]) * os.sysconf('SC_PAGE_SIZE')
        except (FileNotFoundError, ProcessLookupError):
            # /proc entries can vanish between listing and reading.
            continue
    return ticks, rss // MIB


def cpu_delta(previous, current):
    return sum(max(0, value - previous.get(pid, 0)) for pid, value in current.items())


def progress_line(data, previous):
    # Cargo -vv prefixes Ninja lines; keep the latest actual step counter.
    match = list(NINJA.finditer(data))
    if match:
        done, total = map(int, match[-1].groups())
        if 0 <= done <= total and total:
            return done, total
    return previous


def terminate_group(process):
    # Kill the entire process group even if the immediate child already exited.
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        pass
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        pass
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    process.wait()


def run(command, idle=1800, maximum=10800, heartbeat=60, label='build'):
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, start_new_session=True)
    selector = selectors.DefaultSelector()
    selector.register(process.stdout, selectors.EVENT_READ)
    started = last_activity = last_output = last_sample = time.monotonic()
    last_report = started - heartbeat
    previous, rss = group_sample(process.pid)
    progress, carry, cpu_ticks, result = None, b'', 0, None
    sample_interval = min(5, max(.05, idle / 4))
    last_notice = started - 300

    def report(now, notice=False):
        cpus, memory = available_resources()
        disk = shutil.disk_usage(os.getcwd()).free // MIB
        counter = f'{progress[0]}/{progress[1]} ({100 * progress[0] / progress[1]:.1f}%)' if progress else 'not reported'
        elapsed = max(now - started, .001)
        observed = datetime.now(timezone.utc).isoformat(timespec='seconds')
        line = (f'{label}: observed={observed} elapsed={elapsed:.0f}s ninja={counter} '
                f'last_output={now-last_output:.0f}s last_activity={now-last_activity:.0f}s '
                f'processes={len(previous)} sampled_cpu_seconds={cpu_ticks/os.sysconf("SC_CLK_TCK"):.1f} '
                f'process_rss={rss}MiB available_memory={memory}MiB cpu_limit={cpus} free_disk={disk}MiB')
        print(('::notice title=Build progress::' if notice else '[build-progress] ') + line, flush=True)
        return line

    def terminate(signum, frame):
        raise SystemExit(128 + signum)

    old = signal.signal(signal.SIGTERM, terminate)
    old_int = signal.signal(signal.SIGINT, terminate)
    try:
        while True:
            now = time.monotonic()
            if now - last_sample >= sample_interval or now - last_activity >= idle:
                current, rss = group_sample(process.pid)
                delta = cpu_delta(previous, current)
                if delta:
                    last_activity = now
                    cpu_ticks += delta
                previous, last_sample = current, now
            if process.poll() is not None and not selector.get_map():
                result = process.returncode
                return result
            if now - started >= maximum or now - last_activity >= idle:
                reason = 'total time budget' if now - started >= maximum else 'no output or process CPU activity'
                print(f'::error::{label}: exceeded {reason}', flush=True)
                result = 124
                return result
            if now - last_report >= heartbeat:
                notice = now - last_notice >= 300
                report(now, notice)
                last_report = now
                if notice:
                    last_notice = now
            for key, _ in selector.select(timeout=min(1, sample_interval, heartbeat)):
                data = os.read(key.fileobj.fileno(), 65536)
                if data:
                    sys.stdout.buffer.write(data)
                    sys.stdout.buffer.flush()
                    last_activity = last_output = time.monotonic()
                    lines = (carry + data).split(b'\n')
                    progress = progress_line(b'\n'.join(lines[:-1]), progress)
                    carry = lines[-1][-4096:]
                    progress = progress_line(carry, progress)
                else:
                    selector.unregister(key.fileobj)
    finally:
        selector.close()
        current, _ = group_sample(process.pid)
        cpu_ticks += cpu_delta(previous, current)
        terminate_group(process)
        previous, rss = group_sample(process.pid)
        process.stdout.close()
        signal.signal(signal.SIGTERM, old)
        signal.signal(signal.SIGINT, old_int)
        line = report(time.monotonic(), True)
        summary = os.environ.get('GITHUB_STEP_SUMMARY')
        if summary:
            with open(summary, 'a', encoding='utf-8') as stream:
                stream.write(f'\n### {label}\n\n{line}\n\nExit: {result if result is not None else "interrupted"}\n')


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--idle', type=int, default=1800)
    p.add_argument('--maximum', type=int, default=10800)
    p.add_argument('--heartbeat', type=int, default=60)
    p.add_argument('--label', default='build')
    p.add_argument('--jobs', choices=('v8', 'rust'))
    p.add_argument('command', nargs=argparse.REMAINDER)
    a = p.parse_args()
    if a.jobs:
        if a.command:
            p.error('--jobs does not accept a build command')
        cpus, memory = available_resources()
        jobs = choose_jobs(a.jobs, cpus, memory)
        print(f'{a.jobs}: cpu_limit={cpus} available_memory={memory}MiB jobs={jobs}', file=sys.stderr)
        print(jobs)
    else:
        command = a.command[1:] if a.command[:1] == ['--'] else a.command
        if not command or min(a.idle, a.maximum, a.heartbeat) <= 0:
            p.error('A command and positive limits are required')
        raise SystemExit(run(command, a.idle, a.maximum, a.heartbeat, a.label))
