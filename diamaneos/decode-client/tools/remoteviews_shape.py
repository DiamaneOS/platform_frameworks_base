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
"""The shape of RemoteViews, read from its source, as RemoteViewsShapeDeviceTest pins it.

One line per class the decode client's walker (RemoteViewsImages) goes into ("class Name extends Super"), per
instance field ("Name.field: Type", the erased simple name reflection gives), and per int constant that selects what an
action does ("Name.CONSTANT = value": the action tags and value types of RemoteViews, every constant of a walked
class). The device test compares the phone's RemoteViews with tests/device/res/raw/remoteviews_shape.txt.

For a rebase: run --check against the new base's RemoteViews.java. A difference means upstream added, removed or
retyped an action, a field or a constant. Check how the walker handles it (an image in a primitive field, a byte array
or a new value type escapes the generic walk), change the walker if needed, then write the new shape to the file.

Usage: remoteviews_shape.py [--check PINNED] REMOTEVIEWS_JAVA
Exit status: 0 the same shape (or printed), 1 a different shape, 2 usage error.
"""
import re
import sys

HOLDERS = {'BitmapCache', 'ApplicationInfoCache', 'RemoteCollectionItems', 'HierarchyRootData',
           'RemoteCollectionCache', 'RemoteResponse'}


def strip(src):
    """The source with comments, strings and char literals blanked (same length), so braces can be counted."""
    out, i, n = [], 0, len(src)
    while i < n:
        c = src[i]
        if src.startswith('//', i):
            j = src.find('\n', i)
            j = n if j < 0 else j
            out.append(' ' * (j - i))
            i = j
        elif src.startswith('/*', i):
            j = src.find('*/', i + 2)
            j = n if j < 0 else j + 2
            out.append(re.sub(r'[^\n]', ' ', src[i:j]))
            i = j
        elif src.startswith('"""', i):
            j = src.find('"""', i + 3) + 3
            out.append(re.sub(r'[^\n]', ' ', src[i:j]))
            i = j
        elif c in '"\'':
            j = i + 1
            while j < n and src[j] != c:
                j += 2 if src[j] == '\\' else 1
            out.append(c + ' ' * (j - i - 1) + c)
            i = j + 1
        else:
            out.append(c)
            i += 1
    return ''.join(out)


CLASS = re.compile(r'\b(class|interface|enum|record)\s+([A-Za-z_]\w*)([^{;]*)\{')


def classes(text, start, end, prefix=''):
    """{name: (body start, body end, extends)} of the classes declared directly in text[start:end] and, recursively,
    of their nested classes (named by their simple name, as RemoteViews' nested classes are unique)."""
    found = {}
    depth, i = 0, start
    while i < end:
        c = text[i]
        if c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
        elif depth == 0:
            m = CLASS.match(text, i)
            if m and (i == 0 or not (text[i - 1].isalnum() or text[i - 1] == '_')):
                body = m.end()
                d, j = 1, body
                while d:
                    d += {'{': 1, '}': -1}.get(text[j], 0)
                    j += 1
                ext = re.search(r'\bextends\s+([\w.]+)', m[3])
                found[m[2]] = (body, j - 1, ext[1].split('.')[-1] if ext and m[1] == 'class' else '', m[1])
                found.update(classes(text, body, j - 1))
                i = j
                continue
        i += 1
    return found


def members(text, start, end):
    """The statements directly in a class body (depth 0), as strings."""
    out, depth, cur = [], 0, []
    for c in text[start:end]:
        if c == '{':
            if depth == 0:
                cur = []  # a method, initializer or nested class: not a field
            depth += 1
            continue
        if c == '}':
            depth -= 1
            continue
        if depth:
            continue
        if c == ';':
            out.append(''.join(cur).strip())
            cur = []
        else:
            cur.append(c)
    return out


def simple_type(t):
    t = re.sub(r'@[\w.]+(\([^)]*\))?', ' ', t)
    while '<' in t:
        t = re.sub(r'<[^<>]*>', '', t)
    t = t.replace('...', '[]').strip()
    dims = t.count('[')
    base = t.replace('[', '').replace(']', '').strip().split('.')[-1]
    return base + '[]' * dims


MODS = {'public', 'private', 'protected', 'static', 'final', 'transient', 'volatile'}


def fields(stmt):
    """[(static, final, type, name, value)] of a field declaration statement, [] for anything else."""
    # Annotations come off first: their arguments hold '=' and parentheses.
    stmt = re.sub(r'@[\w.]+(\s*\([^()]*(\([^()]*\)[^()]*)*\))?', ' ', stmt)
    head = stmt.split('=', 1)[0]
    if '(' in head or not head.strip() or head.strip().startswith(('return', 'import', 'package')):
        return []  # a method (abstract or native)
    parts = stmt.split('=', 1)
    # Several declarators: int a, b;  The initializer of the last one only is kept.
    words = head.split()
    mods = set()
    while words and words[0] in MODS:
        mods.add(words.pop(0))
    rest = ' '.join(words)
    m = re.match(r'(.+?)\s+([A-Za-z_]\w*(\s*\[\s*\])*(\s*,\s*[A-Za-z_]\w*)*)\s*$', rest)
    if not m:
        return []
    typ = simple_type(m[1])
    value = parts[1].strip() if len(parts) > 1 else ''
    return [('static' in mods, 'final' in mods, typ, n.strip(), value) for n in m[2].split(',')]


def shape(path):
    raw = open(path, encoding='utf-8').read()
    text = strip(raw)
    top = CLASS.search(text)
    cls = classes(text, top.start(), len(text))
    root = top[2]

    def is_action(name, seen=()):
        sup = cls.get(name, (0, 0, '', ''))[2]
        return name == 'Action' or (sup and sup not in seen and is_action(sup, seen + (name,)))

    walked = [root] + sorted(n for n in cls if n != root and (is_action(n) or n in HOLDERS))
    # Int constants by name, so that one given as another (LAYOUT_MARGIN_LEFT = MARGIN_LEFT) gets its value.
    known = {}
    for name, (body, end, _, _) in cls.items():
        for stmt in members(text, body, end):
            for static, final, typ, fname, value in fields(stmt):
                if static and final and typ == 'int' and re.fullmatch(r'-?\d+', value):
                    known.setdefault(fname, value)
    lines = set()
    for name in walked:
        body, end, sup, kind = cls[name]
        lines.add(f'class {name} extends {sup or "Object"}')
        for stmt in members(text, body, end):
            for static, final, typ, fname, value in fields(stmt):
                if not static:
                    lines.add(f'{name}.{fname}: {typ}')
                elif final and typ == 'int' and re.fullmatch(r'-?\d+|[A-Z_][A-Z0-9_]*', value):
                    value = known.get(value, value)
                    if name != root or fname.endswith('_TAG') or fname.startswith(('SET_', 'VALUE_TYPE_')):
                        lines.add(f'{name}.{fname} = {value}')
    return sorted(lines)


def main(argv):
    if len(argv) == 2:
        print('\n'.join(shape(argv[1])))
        return 0
    if len(argv) == 4 and argv[1] == '--check':
        pinned = {l for l in open(argv[2], encoding='utf-8').read().splitlines() if l and not l.startswith('#')}
        now = set(shape(argv[3]))
        for line in sorted(now - pinned):
            print('added upstream:', line)
        for line in sorted(pinned - now):
            print('gone upstream:', line)
        return 1 if now != pinned else 0
    print(__doc__.strip().splitlines()[-2], file=sys.stderr)
    return 2


if __name__ == '__main__':
    sys.exit(main(sys.argv))
