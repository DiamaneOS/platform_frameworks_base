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

Counts the calls that decode an image natively (or load a drawable that may hold an app's image) in SystemUI, the
WM Shell and the system services that show images other apps hand over, and compares them, per file, with the
checked-in allowlist next to this script. Each allowlist line says why that file's calls are fine. A file that is new
or has more calls than its line allows fails the scan: look at the new call, convert it or justify it, then update the
allowlist. Fewer calls than allowed is reported so the line can be tightened.

Usage: native_decode_scan.py [--rev REV] [--update] FRAMEWORKS_BASE_DIR
  --rev REV  scan the tree of a commit (default: the working tree)
  --update   print a fresh allowlist (justifications kept for known files, TODO for new ones)
Exit status: 0 clean, 1 new or grown call sites, 2 usage error.
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


def scan(base, rev):
    cmd = ['git', '-C', base, 'grep', '-c', '-E', PATTERN]
    if rev:
        cmd.append(rev)
    cmd += ['--'] + PATHS
    out = subprocess.run(cmd, capture_output=True, text=True)
    if out.returncode not in (0, 1):
        sys.exit('git grep failed: ' + out.stderr.strip())
    counts = {}
    for line in out.stdout.splitlines():
        path, _, n = line.rpartition(':')
        if rev and path.startswith(rev + ':'):
            path = path[len(rev) + 1:]
        counts[path] = int(n)
    return counts


def read_allowlist():
    allowed = {}
    if not os.path.exists(ALLOWLIST):
        return allowed
    with open(ALLOWLIST) as f:
        for raw in f:
            line = raw.rstrip('\n')
            if not line.strip() or line.startswith('#'):
                continue
            m = re.match(r'(\d+)\s+(\S+)\s+\|\s+(.+)$', line)
            if not m:
                sys.exit('bad allowlist line: ' + line)
            allowed[m.group(2)] = (int(m.group(1)), m.group(3))
    return allowed


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
    counts = scan(args[0], rev)
    allowed = read_allowlist()
    if update:
        for path in sorted(counts):
            why = allowed.get(path, (0, 'TODO: justify'))[1]
            print('%d %s | %s' % (counts[path], path, why))
        return 0
    bad = 0
    for path in sorted(counts):
        n = counts[path]
        if path not in allowed:
            print('NEW   %s: %d call(s), not in the allowlist' % (path, n))
            bad += 1
        elif n > allowed[path][0]:
            print('MORE  %s: %d call(s), %d allowed' % (path, n, allowed[path][0]))
            bad += 1
        elif n < allowed[path][0]:
            print('FEWER %s: %d call(s), %d allowed (tighten the line)' % (path, n, allowed[path][0]))
    for path in sorted(set(allowed) - set(counts)):
        print('GONE  %s: no calls left (remove the line)' % path)
    print('%d file(s) with new or more native-decode calls' % bad)
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
