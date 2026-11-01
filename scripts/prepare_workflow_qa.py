#!/usr/bin/env python3
"""Update one existing QA bundle. Does not launch it or change its bundle identity.

Only run when native testing is explicitly requested. Re-signing a changed binary
can still cause macOS to require approval. Routine tests do not call this script.
"""
import argparse, plistlib, shutil, subprocess
from pathlib import Path
p=argparse.ArgumentParser()
p.add_argument('run',help='Unique output directory label; not an app identity')
p.add_argument('--bundle',type=Path,default=Path('build/qa/workflow-ready/Kotlin CAD Monitor Smoke.app'))
p.add_argument('--kind',choices=['monitor','rack'],default='monitor')
p.add_argument('--send',choices=['return','button'],default='return')
a=p.parse_args()
assert a.run.replace('-','').isalnum()
r=Path(__file__).resolve().parents[1]
bundle=a.bundle.resolve()
assert bundle.is_relative_to(r/'build/qa') and bundle.is_dir(), 'Select an existing QA bundle inside build/qa'
assert not bundle.is_symlink()
home=Path('/private/tmp')/f'kcad-{a.run}'
assert not home.exists(), 'Use a fresh output label to preserve evidence'
(home/'codex').mkdir(parents=True);(home/'codex/test-signed-in').write_text('local protocol fixture')
shutil.copy2(r/'build/libs/kotlin-cad.jar',bundle/'Contents/app/kotlin-cad.jar')
shutil.copytree(r/'build/native-runtime',bundle/'Contents/app/native',dirs_exist_ok=True)
f=bundle/'Contents/Info.plist';d=plistlib.loads(f.read_bytes())
identity=d['CFBundleIdentifier']
d['LSEnvironment']={'KOTLIN_CAD_ASSISTANT_HOME':str(home),'KOTLIN_CAD_BRIDGE_DIR':str(home/'bridge'),'KOTLIN_CAD_CODEX':str(r/'src/test/resources/fake-codex.py'),'KOTLIN_CAD_SMOKE_SEND':a.send}
f.write_bytes(plistlib.dumps(d))
f=next((bundle/'Contents/app').glob('*.cfg'));base=f.read_text().split('[ArgOptions]')[0]
fixture='monitor-mount.stl' if a.kind=='monitor' else 'squat-rack.stl'
f.write_text(base+'[ArgOptions]\n'+''.join('arguments='+v+'\n' for v in ['--assistant-mesh-smoke',a.kind,'--smoke-test',str(r/'build/qa/assistant-mesh-fixtures'/fixture),str(home/'result.png')]))
subprocess.run(['codesign','--force','--deep','--sign','-',str(bundle)],check=True)
assert plistlib.loads((bundle/'Contents/Info.plist').read_bytes())['CFBundleIdentifier']==identity
print(bundle);print(home/'result.png.trace')
