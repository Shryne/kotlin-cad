#!/usr/bin/env python3
"""Conservative source publication guard. Reports locations, never matched values.
No pattern scanner can prove absence of every possible secret. Review the manifest too.
"""
from pathlib import Path
import os,re,subprocess,sys,json,hashlib,zipfile
root=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else Path(__file__).resolve().parents[1]
pruned={'.git','.gradle','.kotlin','build','.idea','__pycache__'}
files=set()
for folder,dirs,names in os.walk(root,followlinks=False):
 dirs[:]=[d for d in dirs if d not in pruned]
 for name in names:files.add(Path(folder)/name)
# Already-tracked ignored files are still checked (including build/private files).
result=subprocess.run(['git','-C',str(root),'ls-files','-z'],capture_output=True)
tracked=set()
if result.returncode==0:
 tracked={root/p for p in result.stdout.decode().split('\0') if p};files.update(tracked)
patterns={
 'API secret':r'sk-[A-Za-z0-9_-]{24,}',
 'GitHub token':r'(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,})',
 'JWT':r'eyJ[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}',
 'AWS access key':r'(?:AKIA|ASIA)[A-Z0-9]{16}',
 'Slack token':r'xox[baprs]-[A-Za-z0-9-]{20,}',
 'Google API key':r'AIza[A-Za-z0-9_-]{30,}',
 'private key':r'-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----',
 'credential URL':r'[a-zA-Z][a-zA-Z0-9+.-]*://[^\s/:@]+:[^\s/@]{8,}@',
 'personal home path':r'(?:/Users/|/home/)[A-Za-z0-9_.-]+/',
 'Windows home path':r'[A-Za-z]:\\Users\\[A-Za-z0-9_.-]+\\',
 'assigned credential':r'''(?i)(?:api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret|password)\s*[=:]\s*["'][A-Za-z0-9_+/=-]{24,}["']''',
}
compiled={name:re.compile(pattern) for name,pattern in patterns.items()}
issues=[]
private_dirs={'models','recovered','CAD Models','.assistant','.codex','.aws','.ssh'}
private_names={'auth.json','chatgpt-auth.json','credentials.json','.env','.DS_Store'}
for path in sorted(files):
 relative=path.relative_to(root)
 if path.is_symlink():issues.append(f'{relative}: symlink is not publishable');continue
 if not path.is_file():continue
 if private_dirs.intersection(relative.parts) or path.name in private_names or (path.name.startswith('.env.') and path.name!='.env.example') or path.suffix.lower() in {'.pem','.key','.p12','.pfx'}:
  issues.append(f'{relative}: private configuration or project file');continue
 # Generated synthetic fixtures are intentionally ignored by Git; never include them in the source archive.
 if relative.parts[0]=='samples' and path not in tracked:continue
 if path.suffix.lower() in {'.kcad','.stl','.obj','.step','.stp','.3mf','.blend'}:
  issues.append(f'{relative}: model/project asset is not publishable');continue
 if path.suffix.lower()=='.png':
  approvals=root/'docs/images/approved-assets.json'
  approved=json.loads(approvals.read_text()) if approvals.is_file() else {}
  record=approved.get(path.name) if relative.parent==Path('docs/images') else None
  if not record or hashlib.sha256(path.read_bytes()).hexdigest()!=record.get('sha256'):
   issues.append(f'{relative}: unreviewed or changed image')
  continue
 try:content=path.read_text()
 except UnicodeError:
  if str(relative)!='gradle/wrapper/gradle-wrapper.jar':issues.append(f'{relative}: unexpected binary file')
  else:
   expected=json.loads((root/'gradle/wrapper/checksums.json').read_text())['gradle-8.13-wrapper.jar']
   if hashlib.sha256(path.read_bytes()).hexdigest()!=expected:issues.append(f'{relative}: wrapper checksum mismatch')
   with zipfile.ZipFile(path) as archive:
    for entry in archive.infolist():
     if entry.file_size>10_000_000:issues.append(f'{relative}: oversized archive entry');continue
     content=archive.read(entry).decode('latin1')
     if any(p.search(content) for p in compiled.values()):issues.append(f'{relative}: possible sensitive content in wrapper archive')
  continue
 except OSError:continue
 for number,line in enumerate(content.splitlines(),1):
  for label,pattern in compiled.items():
   if pattern.search(line):issues.append(f'{relative}:{number}: possible {label}')
if issues:
 print('Publication check failed (values hidden):\n'+'\n'.join(issues),file=sys.stderr);sys.exit(1)
print(f'SOURCE_CREDENTIAL_CHECK_OK files={len(files)}')
