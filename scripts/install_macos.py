#!/usr/bin/env python3
"""Replace only this app, preserving one backup; refuse to replace a running installation."""
from pathlib import Path
import os
import json
import re
import plistlib
import shutil
import subprocess
import sys
import tempfile

source, target = map(lambda value: Path(value).absolute(), sys.argv[1:])
identifier = 'local.kotlincad.viewer'

def validate(app):
    metadata = plistlib.loads((app / 'Contents/Info.plist').read_bytes())
    if metadata.get('CFBundleIdentifier') != identifier:
        raise RuntimeError(f'Keine Kotlin-CAD-Installation: {app}')

validate(source)
if target.is_symlink():
    raise RuntimeError('Das Installationsziel muss ein echter App-Ordner sein.')
if target.exists():
    validate(target)
    running = subprocess.check_output(['/bin/ps', '-axo', 'command='], text=True)
    if str(target / 'Contents/MacOS/Kotlin CAD') in running:
        raise RuntimeError('Bitte Kotlin CAD vor dem Aktualisieren schließen. Die vorhandene App bleibt erhalten.')
expected = subprocess.check_output([str(source / 'Contents/MacOS/Kotlin CAD'), '--version'], text=True).strip()
if not re.fullmatch(r'Kotlin CAD [0-9]+\.[0-9]+\.[0-9]+ \(Build [a-f0-9]{12}\)', expected):
    raise RuntimeError(f'Das Paket enthält keine gültige Versionskennung: {expected}')
version_tuple=tuple(map(int, re.search(r'Kotlin CAD ([0-9]+\.[0-9]+\.[0-9]+)',expected).group(1).split('.')))
if version_tuple >= (1,38,0):
    result=json.loads(subprocess.check_output([str(source / 'Contents/MacOS/Kotlin CAD'),'--kernel-check'],
        text=True,timeout=45,env={'PATH':'/usr/bin:/bin','LANG':'en_US.UTF-8'}))
    manifest=json.loads((source / 'Contents/app/native/manifest.json').read_text())
    if result.get('kernel')!=manifest.get('occt') or result.get('protocol')!=1 or len(result.get('fixtures',[]))!=5 or not all(result.get('solver',{}).values()):
        raise RuntimeError('Der mitgelieferte native Geometriekern besteht seine Prüfung nicht.')
target.parent.mkdir(parents=True, exist_ok=True)
backup = target.with_name('.Kotlin CAD.previous')
if backup.exists():
    validate(backup)
    shutil.rmtree(backup)
staging = Path(tempfile.mkdtemp(prefix='.Kotlin-CAD-install-', dir=target.parent))
candidate = staging / target.name
try:
    shutil.copytree(source, candidate, symlinks=True)
    actual = subprocess.check_output([str(candidate / 'Contents/MacOS/Kotlin CAD'), '--version'], text=True).strip()
    if actual != expected:
        raise RuntimeError('Die kopierte App entspricht nicht dem neuen Build.')
    if target.exists():
        os.replace(target, backup)
    try:
        os.replace(candidate, target)
    except Exception:
        if backup.exists() and not target.exists():
            os.replace(backup, target)
        raise
finally:
    shutil.rmtree(staging)
print(f'Installiert: {target}\n{expected}')
