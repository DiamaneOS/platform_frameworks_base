#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Build a small public-API parser test using an existing Android SDK and JDK."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument('--sdk', type=Path, required=True)
parser.add_argument('--jdk', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parent
out = args.output.resolve()
out.mkdir(mode=0o700)
tools = args.sdk / 'build-tools/36.0.0'
android = args.sdk / 'platforms/android-36/android.jar'
env = os.environ.copy()
env['JAVA_HOME'] = str(args.jdk)
def run(command):
    subprocess.run([str(v) for v in command], check=True, env=env)

classes = out / 'classes'
classes.mkdir()
run([args.jdk/'bin/javac', '--release', '8', '-classpath', android, '-d', classes,
     root/'SensorOptOutTests.java'])
dex = out/'dex'
dex.mkdir()
run([tools/'d8', '--lib', android, '--min-api', '23', '--output', dex,
     *sorted(classes.rglob('*.class'))])

fixtures = out/'fixtures'
fixtures.mkdir()
key = 'de.diamaneos.permission.NO_IMPLICIT_OTHER_SENSORS'
variants = {
    'absent': ('', '', True, True),
    'false': (f'<meta-data android:name="{key}" android:value="false"/>', '', True, True),
    'wrong_type': (f'<meta-data android:name="{key}" android:value="not_boolean"/>', '', True, True),
    'true': (f'<meta-data android:name="{key}" android:value="true"/>', '', True, False),
    'explicit': (f'<meta-data android:name="{key}" android:value="true"/>',
                 '<uses-permission android:name="android.permission.OTHER_SENSORS"/>', True, True),
    'no_code': ('', '', False, False),
}
for name, (metadata, permission, has_code, expected) in variants.items():
    manifest = fixtures/(name+'.xml')
    manifest.write_text(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android"
        package="de.diamaneos.sensorfixture.{name.replace('_', '')}">
        <uses-sdk android:minSdkVersion="23" android:targetSdkVersion="36"/>
        {permission}
        <application android:hasCode="{str(has_code).lower()}" android:allowBackup="false">
          {metadata}
        </application>
      </manifest>''')
    archive = fixtures/(name+'.apk')
    run([tools/'aapt2', 'link', '-I', android, '--manifest', manifest, '-o', archive])
    if has_code:
        with zipfile.ZipFile(archive, 'a') as target:
            target.write(dex/'classes.dex', 'classes.dex')

unsigned = out/'unsigned.apk'
run([tools/'aapt2', 'link', '-I', android, '--manifest', root/'AndroidManifest.xml', '-o', unsigned])
with zipfile.ZipFile(unsigned, 'a') as target:
    target.write(dex/'classes.dex', 'classes.dex')
    for archive in sorted(fixtures.glob('*.apk')):
        target.write(archive, 'assets/'+archive.name)
aligned = out/'aligned.apk'
run([tools/'zipalign', '-f', '4', unsigned, aligned])
# A disposable test identity, never a platform or release signing key.
keystore = out/'test-only.jks'
password = 'diamaneos-parser-tests-only'
run([args.jdk/'bin/keytool', '-genkeypair', '-noprompt', '-alias', 'test',
     '-keystore', keystore, '-storepass', password, '-keypass', password,
     '-keyalg', 'RSA', '-keysize', '3072', '-validity', '30',
     '-dname', 'CN=DiamaneOS disposable parser test'])
apk = out/'SensorOptOutTests.apk'
run([tools/'apksigner', 'sign', '--ks', keystore, '--ks-key-alias', 'test',
     '--ks-pass', 'pass:'+password, '--out', apk, aligned])
run([tools/'apksigner', 'verify', apk])
record = {'apk': str(apk), 'sha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
          'bytes': apk.stat().st_size, 'expected_requests': {k:v[3] for k,v in variants.items()},
          'declared_permissions': [], 'public_apis_only': True,
          'fixtures_installed': False, 'native_acceptance': 'PENDING'}
(out/'artifact.json').write_text(json.dumps(record, indent=2)+'\n')
print(json.dumps(record), flush=True)
