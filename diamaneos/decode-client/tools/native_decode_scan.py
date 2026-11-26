#!/usr/bin/env python3
# Copyright (C) 2026 DiamaneOS
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""Native-decode call-site scan for a rebase of frameworks/base.

Finds the calls that decode an image natively (or load a drawable that may hold an app's image) in SystemUI, the
WM Shell and the system services that show images other apps hand over, and compares each call site with the
checked-in allowlist next to this script. A call site is known by its file and its source line (spaces collapsed),
not by its line number, so a moved call stays allowed and a call replaced by another in the same file is new. The
allowlist has one entry per file: "<path> | <why its calls are fine>", then its call lines, each indented. A call
site that is not listed (or is listed fewer times than it occurs) fails the scan: look at the new call, convert it or
justify it, then update the allowlist. A listed call that is gone is reported so the entry can be tightened.

Usage: native_decode_scan.py [--rev REV] [--update] FRAMEWORKS_BASE_DIR
  --rev REV  scan the tree of a commit (default: the working tree)
  --update   print a fresh allowlist (justifications kept for known files, TODO for new ones)
Exit status: 0 clean, 1 new call sites, 2 usage error.
"""
import os
import re
import subprocess
import sys

PATTERN = (r'BitmapFactory\.decode|ImageDecoder\.(createSource|decode)|Drawable\.createFrom|loadThumbnail\(|'
           r'\.loadDrawable(AsUser|Async)?\(|getApplicationIcon\(|\.loadIcon\(|loadUnbadgedIcon\(|'
           r'getActivityIcon\(|getShortcutIconDrawable\(|getShortcutIcon\(|'
           # Views and layouts handed a link or an Icon decode it themselves, and plain resources or a context of
           # another package decode that package's image files: where an app's image would load outside the gate.
           r'setImageURI\(|setImageIcon\(|setImageViewUri\(|setImageViewIcon\(|'
           r'getResourcesForApplication(AsUser)?\(|createPackageContext(AsUser)?\(|createApplicationContext\(')
PATHS = [
    'packages/SystemUI/src',
    'libs/WindowManager/Shell/src',
    'services/core/java/com/android/server',
    'services/autofill',
    'services/companion',
    'services/accessibility',
]
ALLOWLIST = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'native_decode_allowlist.txt')


def call_text(line):
    """A call site's identity within its file: the source line with its spaces collapsed."""
    return ' '.join(line.split())


def scan(base, rev):
    """{path: [call text, ...]} of every matching line, in file order."""
    cmd = ['git', '-C', base, 'grep', '-I'] + ([] if rev else ['--untracked']) + ['-E', PATTERN]
    if rev:
        cmd.append(rev)
    cmd += ['--'] + PATHS
    out = subprocess.run(cmd, capture_output=True, text=True)
    if out.returncode not in (0, 1):
        sys.exit('git grep failed: ' + out.stderr.strip())
    calls = {}
    for line in out.stdout.splitlines():
        if rev and line.startswith(rev + ':'):
            line = line[len(rev) + 1:]
        path, sep, text = line.partition(':')
        if not sep:
            continue
        calls.setdefault(path, []).append(call_text(text))
    return calls


def read_allowlist():
    """{path: (why, [call text, ...])}."""
    allowed = {}
    if not os.path.exists(ALLOWLIST):
        return allowed
    path = None
    with open(ALLOWLIST) as f:
        for raw in f:
            line = raw.rstrip('\n')
            if not line.strip() or line.startswith('#'):
                continue
            if line[0].isspace():
                if path is None:
                    sys.exit('call line before any file: ' + line)
                allowed[path][1].append(call_text(line))
                continue
            m = re.match(r'(\S+)\s+\|\s+(.+)$', line)
            if not m:
                sys.exit('bad allowlist line: ' + line)
            path = m.group(1)
            if path in allowed:
                sys.exit('file listed twice: ' + path)
            allowed[path] = (m.group(2), [])
    return allowed


def counted(texts):
    n = {}
    for t in texts:
        n[t] = n.get(t, 0) + 1
    return n


def main(argv):
    rev = None
    update = False
    args = []
    i = 0
    while i < len(argv):
        if argv[i] == '--rev' and i + 1 < len(argv):
            rev = argv[i + 1]
            i += 2
            continue
        if argv[i] == '--update':
            update = True
        else:
            args.append(argv[i])
        i += 1
    if len(args) != 1:
        print(__doc__)
        return 2
    calls = scan(args[0], rev)
    allowed = read_allowlist()
    if update:
        for path in sorted(calls):
            why = allowed.get(path, ('TODO: justify', []))[0]
            print('%s | %s' % (path, why))
            for text in calls[path]:
                print('    ' + text)
        return 0
    bad = 0
    for path in sorted(calls):
        if path not in allowed:
            print('NEW   %s: %d call(s), not in the allowlist' % (path, len(calls[path])))
            for text in calls[path]:
                print('        ' + text)
            bad += 1
            continue
        have = counted(calls[path])
        may = counted(allowed[path][1])
        new = [t for t in have if have[t] > may.get(t, 0)]
        for t in new:
            print('NEW   %s: %s' % (path, t))
        if new:
            bad += 1
        for t in may:
            if may[t] > have.get(t, 0):
                print('GONE  %s: %s (tighten the entry)' % (path, t))
    for path in sorted(set(allowed) - set(calls)):
        print('GONE  %s: no calls left (remove the entry)' % path)
    print('%d file(s) with new native-decode call sites' % bad)
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
