#!/usr/bin/env python3
"""Acceptance against an already-running EMPTY viewer. Restores an explicit saved project."""
import json,subprocess,selectors,sys
from pathlib import Path
root=Path(__file__).resolve().parents[1]
restore=Path(sys.argv[1]).resolve();assert restore.is_file()
app=str(Path.home()/'Applications/Kotlin CAD.app/Contents/MacOS/Kotlin CAD')
p=subprocess.Popen([app,'--mcp'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL)
sel=selectors.DefaultSelector();sel.register(p.stdout,selectors.EVENT_READ);seq=0

def request(method,params):
 global seq
 seq+=1;p.stdin.write((json.dumps(dict(jsonrpc='2.0',id=seq,method=method,params=params))+'\n').encode());p.stdin.flush()
 while True:
  assert sel.select(60),'MCP timed out'
  line=p.stdout.readline();assert line,'MCP closed'
  result=json.loads(line)
  if result.get('id')==seq:
   assert 'error' not in result,result
   return result['result']
def call(name,args=None,error=False):
 r=request('tools/call',dict(name=name,arguments=args or {}));assert bool(r.get('isError'))==error,r
 return r if error else r.get('structuredContent') or json.loads(r['content'][0]['text'])
def section(z,x='0 mm',ry='6 mm',twist='0 deg'):
 return dict(x=x,y='0 mm',z=z,radiusX='10 mm',radiusY=ry,rotationX='0 deg',rotationY='0 deg',rotationZ=twist)
owns_session=False
try:
 request('initialize',dict(protocolVersion='2024-11-05',capabilities={},clientInfo=dict(name='loft-acceptance',version='1')))
 p.stdin.write(b'{"jsonrpc":"2.0","method":"notifications/initialized"}\n');p.stdin.flush()
 tools=request('tools/list',{})['tools'];assert {'cad_preview_loft','cad_create_loft','cad_update_loft'}<={t['name'] for t in tools}
 initial=call('cad_get_model');assert not initial['features'] and not initial['project']['dirty'],'Requires empty project'
 owns_session=True
 args=dict(name='Loft acceptance',sections=[section('0 mm'),section('20 mm','3 mm',twist='20 deg'),section('40 mm','8 mm','4 mm','40 deg')])
 preview=call('cad_preview_loft',args);assert preview['validation']['closed'];assert not call('cad_get_model')['features']
 created=call('cad_create_loft',args);id=created['featureId'];assert id==created['bodyId']
 args['sections'][-1]['z']='50 mm'
 updated=call('cad_update_loft',dict(id=id,sections=args['sections'],ruled=True));assert updated['featureId']==id and updated['bounds']['max']['z']==50
 assert updated['feature']['name']=='Loft acceptance'
 bad=[dict(s) for s in args['sections']];bad[-1]['radiusX']='-1 mm'
 call('cad_update_loft',dict(id=id,sections=bad),True)
 assert call('cad_get_feature',dict(id=id))['feature']==updated['feature']
 call('cad_undo');assert call('cad_get_feature',dict(id=id))['feature']['sections'][-1]['z']=='40 mm'
 call('cad_redo');assert call('cad_get_feature',dict(id=id))['feature']['sections'][-1]['z']=='50 mm'
 out=root/'build/qa/loft';out.mkdir(parents=True,exist_ok=True)
 call('cad_save_project',dict(path=str(out/'acceptance.kcad'),overwrite=True))
 call('cad_open_project',dict(path=str(out/'acceptance.kcad')))
 assert call('cad_get_feature',dict(id=id))['feature']==updated['feature']
 for ext in ['stl','obj']:call('cad_export_mesh',dict(path=str(out/('acceptance.'+ext)),overwrite=True))
 (out/'mcp-result.json').write_text(json.dumps(dict(application=initial['application'],preview=preview,created=created,updated=updated),indent=2))
 print('LOFT_MCP_OK preview/create/update/rejection/undo/redo/save/load/STL/OBJ',initial['application'])
finally:
 try:
  if owns_session:
   restored=call('cad_open_project',dict(path=str(restore),discardChanges=True));print('Restored saved project:',len(restored['sceneBodies']),'bodies')
 finally:p.terminate();p.wait(timeout=10)
