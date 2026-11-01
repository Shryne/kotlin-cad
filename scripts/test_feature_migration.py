#!/usr/bin/env python3
"""Differential geometry check: read-only legacy app vs new feature model, isolated viewers."""
import hashlib,json,os,queue,subprocess,sys,tempfile,threading,time
from pathlib import Path
root=Path(__file__).resolve().parents[1]
legacy=Path(sys.argv[1]);current=Path(sys.argv[2]);report=root/'build/qa/feature-migration.json'
cases=[root/'samples'/f'{name}.kcad' for name in ['bracket','sketches','extrusions','mirrors']]
originals={str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in cases}

class Viewer:
 def __init__(self,binary,folder):
  self.folder=folder;self.folder.mkdir();env=os.environ.copy();env['KOTLIN_CAD_BRIDGE_DIR']=str(folder/'bridge');env['KOTLIN_CAD_ASSISTANT_HOME']=str(folder/'assistant')
  env['JAVA_HOME']=subprocess.check_output(['/usr/libexec/java_home','-v17'],text=True).strip()
  self.log=(folder/'app.log').open('w');self.gui=subprocess.Popen([str(binary)],cwd=root,env=env,stdout=self.log,stderr=self.log)
  socket=folder/'bridge'/f'cad-{self.gui.pid}.sock'
  for _ in range(150):
   if socket.exists():break
   assert self.gui.poll() is None,'Viewer exited'
   time.sleep(.1)
  assert socket.exists(),'Missing bridge'
  self.adapter=subprocess.Popen([str(binary),'--mcp','--socket',str(socket)],cwd=root,env=env,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=self.log,text=True,bufsize=1)
  self.responses=queue.Queue();threading.Thread(target=lambda:[self.responses.put(line) for line in self.adapter.stdout],daemon=True).start();self.seq=0
  self.request('initialize',dict(protocolVersion='2025-06-18',capabilities={},clientInfo=dict(name='migration-regression',version='1')))
  self.adapter.stdin.write('{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}\n');self.adapter.stdin.flush()
 def request(self,method,params):
  self.seq+=1;self.adapter.stdin.write(json.dumps(dict(jsonrpc='2.0',id=self.seq,method=method,params=params))+'\n');self.adapter.stdin.flush()
  while True:
   response=json.loads(self.responses.get(timeout=40))
   if response.get('id')==self.seq:
    assert 'error' not in response,response;return response['result']
 def call(self,name,args={}):
  response=self.request('tools/call',dict(name=name,arguments=args));assert not response.get('isError'),response
  return response.get('structuredContent') or json.loads(response['content'][0]['text'])
 def close(self):
  self.adapter.stdin.close();self.adapter.wait(timeout=10);self.gui.terminate();self.gui.wait(timeout=10);self.log.close()

with tempfile.TemporaryDirectory(prefix='cad-feature-migration-',dir='/private/tmp') as temporary:
 folder=Path(temporary);baseline={};results=[]
 old=Viewer(legacy,folder/'legacy')
 try:
  old.call('cad_open_file',dict(path=str(root/'samples/bracket.obj'),discardChanges=True))
  parent=old.call('cad_set_sketch',dict(name='Parent',plane='XY',shape='RECTANGLE',u='10 mm',v='0 mm',width='10 mm',height='5 mm',depth='8 mm',mirror='YZ'))
  mirror=next(b for b in parent['sceneBodies'] if b['mirrored'])
  offset=0
  for body in parent['sceneBodies']:
   if body['id']==mirror['id']:break
   offset+=body['triangles']
  old.call('cad_set_sketch',dict(name='On reflected face',plane='XY',shape='RECTANGLE',u='1 mm',v='1 mm',width='2 mm',height='2 mm',depth='2 mm',supportFace=offset+1))
  old.call('cad_set_body_visibility',dict(id=mirror['id'],visible=False))
  custom=folder/'mirrored-support.kcad';old.call('cad_save_project',dict(path=str(custom)));cases.append(custom)
  originals[str(custom)]=hashlib.sha256(custom.read_bytes()).hexdigest()
  for index,path in enumerate(cases):
   model=old.call('cad_open_project',dict(path=str(path),discardChanges=True));export=folder/f'old-{index}.stl'
   old.call('cad_export_mesh',dict(path=str(export)));baseline[str(path)]=(export.read_bytes(),model)
 finally:old.close()
 new=Viewer(current,folder/'current')
 try:
  for index,path in enumerate(cases):
   model=new.call('cad_open_project',dict(path=str(path),discardChanges=True));export=folder/f'new-{index}.stl'
   new.call('cad_export_mesh',dict(path=str(export)));expected,before=baseline[str(path)]
   assert export.read_bytes()==expected,f'Geometry changed: {path}'
   for key in ['parameters','display','bodies','sceneBodies']:assert model[key]==before[key],(path,key)
   assert model['schemaVersion']==10
   saved=folder/f'roundtrip-{index}.kcad';new.call('cad_save_project',dict(path=str(saved)))
   new.call('cad_open_project',dict(path=str(saved),discardChanges=True));again=folder/f'again-{index}.stl'
   new.call('cad_export_mesh',dict(path=str(again)));assert again.read_bytes()==expected,f'Roundtrip changed: {path}'
   results.append(dict(project=str(path.relative_to(root)) if path.is_relative_to(root) else path.name,sha256=hashlib.sha256(expected).hexdigest(),bytes=len(expected),legacyCompatible=True,v10Roundtrip=True))
 finally:new.close()
 for path,digest in originals.items():assert hashlib.sha256(Path(path).read_bytes()).hexdigest()==digest,'Input file was modified'
report.write_text(json.dumps(dict(fixtures=results,sourceFilesUnchanged=True),indent=2)+'\n')
print('FEATURE_MIGRATION_OK fixtures='+str(len(results))+' byteExactSTL=true v10Roundtrip=true originalsUnchanged=true')
