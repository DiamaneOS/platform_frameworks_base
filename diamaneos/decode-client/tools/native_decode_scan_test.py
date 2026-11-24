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
"""Tests of native_decode_scan.py on a small git tree of its own: python3 native_decode_scan_test.py"""
import contextlib
import importlib.util
import io
import os
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location('native_decode_scan', os.path.join(HERE, 'native_decode_scan.py'))
scan = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scan)

VIEW = 'packages/SystemUI/src/com/example/View.java'
OTHER = 'packages/SystemUI/src/com/example/Other.java'


def git(tree, *args):
    subprocess.run(['git', '-C', tree] + list(args), check=True, capture_output=True)


class ScanTest(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.tree = os.path.join(self.dir.name, 'fb')
        os.makedirs(os.path.join(self.tree, os.path.dirname(VIEW)))
        git(self.tree, 'init', '-q')
        self.write(VIEW, ['class View {', '    void a() {', '        icon.loadDrawable(context);', '    }',
                          '    void b() {', '        Drawable.createFrom(stream, null);', '    }', '}'])
        self.commit()
        self.allowlist = os.path.join(self.dir.name, 'allow.txt')
        scan.ALLOWLIST = self.allowlist
        code, out = self.run_scan('--update')
        self.assertEqual(0, code)
        with open(self.allowlist, 'w') as f:
            f.write(out.replace('TODO: justify', 'checked'))

    def tearDown(self):
        self.dir.cleanup()

    def write(self, path, lines):
        with open(os.path.join(self.tree, path), 'w') as f:
            f.write('\n'.join(lines) + '\n')

    def commit(self):
        git(self.tree, 'add', '-A')
        git(self.tree, '-c', 'user.name=t', '-c', 'user.email=t@t', 'commit', '-q', '-m', 'c')

    def run_scan(self, *args):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = scan.main(list(args) + [self.tree])
        return code, out.getvalue()

    def test_unchanged_tree_isClean(self):
        self.assertEqual(0, self.run_scan()[0])

    def test_aCallReplacedByAnotherInTheSameFile_fails(self):
        # The same number of calls, one of them new: a per-file count cannot see it.
        self.write(VIEW, ['class View {', '    void a() {', '        icon.loadDrawable(context);', '    }',
                          '    void b() {', '        BitmapFactory.decodeStream(stream);', '    }', '}'])
        code, out = self.run_scan()
        self.assertEqual(1, code, out)
        self.assertIn('BitmapFactory.decodeStream(stream);', out)

    def test_aCallReplacedByAnotherInTheSameFile_failsAtARevision(self):
        self.write(VIEW, ['class View {', '    void a() {', '        icon.loadDrawable(context);', '    }',
                          '    void b() {', '        BitmapFactory.decodeStream(stream);', '    }', '}'])
        self.commit()
        self.assertEqual(1, self.run_scan('--rev', 'HEAD')[0])

    def test_aCallThatOnlyMoved_isClean(self):
        self.write(VIEW, ['class View {', '    void b() {', '        Drawable.createFrom(stream, null);', '    }',
                          '', '    void a() {', '        icon.loadDrawable(context);', '    }', '}'])
        code, out = self.run_scan()
        self.assertEqual(0, code, out)

    def test_aSecondCopyOfAnAllowedCall_fails(self):
        self.write(VIEW, ['class View {', '    void a() {', '        icon.loadDrawable(context);', '    }',
                          '    void b() {', '        Drawable.createFrom(stream, null);', '    }',
                          '    void c() {', '        icon.loadDrawable(context);', '    }', '}'])
        self.assertEqual(1, self.run_scan()[0])

    def test_aNewFile_fails(self):
        self.write(OTHER, ['class Other {', '    Bitmap b = BitmapFactory.decodeByteArray(d, 0, n);', '}'])
        code, out = self.run_scan()
        self.assertEqual(1, code)
        self.assertIn('NEW', out)

    def test_aRemovedCall_isReported_notFailed(self):
        self.write(VIEW, ['class View {', '    void a() {', '        icon.loadDrawable(context);', '    }', '}'])
        code, out = self.run_scan()
        self.assertEqual(0, code)
        self.assertIn('Drawable.createFrom(stream, null);', out)


if __name__ == '__main__':
    unittest.main()
