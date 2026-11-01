#!/usr/bin/env python3
"""Build-time only: bundle a native executable and its private dylib closure."""
from pathlib import Path
import json, shutil, subprocess, sys, os, platform
root,prefix,source=map(lambda s:Path(s).resolve(),sys.argv[1:])
assert root.name=='native-runtime' and root.parent.name=='build', 'Unexpected bundle destination'
if (root/'lib').exists():shutil.rmtree(root/'lib')
(root/'lib').mkdir();(root/'licenses').mkdir(exist_ok=True)

def dependencies(path):
    return [line.strip().split(' (',1)[0] for line in subprocess.check_output(['/usr/bin/otool','-L',str(path)],text=True).splitlines()[1:]]
queue=[root/'cad-kernel'];seen=set()
while queue:
    target=queue.pop(0)
    if target in seen:continue
    seen.add(target)
    for dependency in dependencies(target):
        if dependency.startswith(('/usr/lib/','/System/Library/')):continue
        name=Path(dependency).name
        candidate=prefix/'lib'/name
        if not candidate.exists():
            # The dylib's own rewritten identity is not a dependency.
            if name==target.name:continue
            raise RuntimeError(f'Unbundled dependency: {dependency}')
        bundled=root/'lib'/name
        if not bundled.exists():shutil.copy2(candidate.resolve(),bundled)
        relative=('@loader_path/' if target.parent==root/'lib' else '@loader_path/lib/')+name
        subprocess.run(['/usr/bin/install_name_tool','-change',dependency,relative,str(target)],check=True)
        if target.parent==root/'lib':subprocess.run(['/usr/bin/install_name_tool','-id','@rpath/'+target.name,str(target)],check=True)
        queue.append(bundled)
for path in seen:
    for dependency in dependencies(path):
        if not dependency.startswith(('/usr/lib/','/System/Library/','@loader_path/','@rpath/')):
            raise RuntimeError(f'External runtime path: {dependency}')
    subprocess.run(['/usr/bin/codesign','--force','--sign','-',str(path)],check=True,stderr=subprocess.DEVNULL)
for name in ['LICENSE_LGPL_21.txt','OCCT_LGPL_EXCEPTION.txt']:
    shutil.copy2(source/name,root/'licenses'/name)
shutil.copy2(Path('native/third_party/nlohmann/LICENSE.MIT'),root/'licenses/JSON-LICENSE.MIT')
eigen=Path(os.environ.get('CAD_EIGEN_PREFIX') or subprocess.check_output(['brew','--prefix','eigen'],text=True).strip())
for name in ['COPYING.MPL2','COPYING.BSD','COPYING.APACHE','COPYING.MINPACK','COPYING.README','LICENSE']:
    if (eigen/name).exists():shutil.copy2(eigen/name,root/'licenses'/('EIGEN-'+name))
(root/'licenses/NOTICE.txt').write_text('Kotlin CAD native geometry worker\nOpenCascade Technology 8.0.1: LGPL 2.1 with OCCT exception.\nSource: https://github.com/Open-Cascade-SAS/OCCT/tree/V8_0_1\nBuilt from unmodified upstream sources; dynamic libraries can be replaced subject to ABI compatibility.\nBuild recipe: scripts/build-native.sh.\nEigen 5.0.1: see bundled Eigen license texts.\nnlohmann/json 3.12.0: MIT.\nThis distribution does not contain Python, OCP, VTK or Qt.\n')
(root/'manifest.json').write_text(json.dumps({'protocol':1,'occt':'8.0.1','eigen':'5.0.1','json':'3.12.0','architecture':platform.machine(),'libraries':sorted(p.name for p in seen if p.suffix=='.dylib')},indent=2)+'\n')
print('NATIVE_BUNDLE_OK',len(seen),'Mach-O files')
