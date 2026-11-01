#!/usr/bin/env python3
"""Installed-app assembly acceptance. Requires an empty viewer; restores a saved project."""
import sys,json,time,base64
from pathlib import Path
from mcp_client import CadClient
root=Path(__file__).resolve().parents[1];out=root/'build/qa/assemblies';out.mkdir(parents=True,exist_ok=True)
restore=Path(sys.argv[1]).resolve();assert restore.is_file()
def value(r):return json.loads(next(i['text'] for i in r['content'] if i['type']=='text'))
with CadClient() as c:
 def call(tool,args={}):return value(c.call(tool,args))
 def wait(job,expected='complete'):
  deadline=time.monotonic()+90
  while True:
   r=call('cad_get_assembly_job',{'jobId':job['jobId']})
   if r['status'] not in ['running','committing']:assert r['status']==expected,r;return r
   assert time.monotonic()<deadline,r;time.sleep(.1)
 initial=call('cad_get_model');assert not initial['features'] and not initial.get('model') and not initial['project']['dirty']
 try:
  tools=c.request('tools/list',{})['tools'];names={'cad_apply_batch','cad_get_assembly_job','cad_cancel_assembly_job','cad_create_instance','cad_update_instance','cad_check_assembly_path','cad_check_retention','cad_check_manufacturing','cad_highlight_manufacturing','cad_get_assembly','cad_compare_assembly','cad_preview_exploded','cad_clear_assembly_preview'}
  schemas=[t for t in tools if t['name'] in names];assert len(schemas)==13
  (root/'docs/api/assembly-tools.schema.json').write_text(json.dumps({'tools':schemas},indent=2)+'\n')
  feature={'type':'nativeShape','id':'plate','name':'Shared plate','shape':{'type':'box','x':{'expression':'width'},'y':10,'z':.4},'singleSolid':True}
  job=call('cad_apply_batch',{'operations':[{'op':'set_parameter','name':'width','expression':'20 mm'},{'op':'put_feature','feature':feature},{'op':'put_feature','feature':{'type':'instance','id':'copy','name':'Turned plate','sourceFeatureId':'plate','x':'35 mm','rotationZ':'90 deg'}},{'op':'set_body_color','bodyId':'plate','color':'#3399CC'}]})
  created=wait(job);assert created['result']['bodyCount']==2
  state=call('cad_get_model');ids=[b['id'] for b in state['sceneBodies']];assert ids==['plate','copy']
  assert state['sceneBodies'][1]['appearance']['effectiveColor']=='#3399CC'
  call('cad_undo');assert not call('cad_get_model')['features'] and not call('cad_get_model')['parameters'];call('cad_redo')
  baseline=call('cad_get_model');invalid=call('cad_apply_batch',{'operations':[{'op':'set_parameter','name':'width','expression':'-1 mm'},{'op':'set_body_color','bodyId':'plate','color':'#FF0000'}]});wait(invalid,'failed');assert call('cad_get_model')['sceneBodies']==baseline['sceneBodies']
  updated=wait(call('cad_apply_batch',{'operations':[{'op':'set_parameter','name':'width','expression':'24 mm'},{'op':'set_body_color','bodyId':'copy','color':'#55CC66'}]}));assert updated['result']['geometryBuilds']==2,updated # source geometry + transformed linked instance
  assert call('cad_get_model')['sceneBodies'][1]['dimensions']['y']==24
  colors=wait(call('cad_apply_batch',{'operations':[{'op':'set_body_color','bodyId':'plate','color':'#4477DD'}]}));assert colors['result']['geometryBuilds']==0,colors
  call('cad_save_project',{'path':str(out/'assembly.kcad'),'overwrite':True});call('cad_open_project',{'path':str(out/'assembly.kcad')})
  saved=call('cad_get_model');assert saved['sceneBodies'][1]['appearance']['effectiveColor']=='#55CC66'
  counts=call('cad_get_assembly');assert counts['bodyCount']==2 and counts['parts'][0]['count']==2
  report=wait(call('cad_check_manufacturing',{'bodyIds':['plate','copy'],'minWallMm':.8}));assert report['result']['warnings']
  call('cad_highlight_manufacturing',{'jobId':report['jobId']});call('cad_clear_assembly_preview')
  call('cad_preview_exploded',{'distanceMm':12})
  for block in c.call('cad_get_preview',{'maxSize':1200})['content']:
   if block['type']=='image':(out/'exploded.png').write_bytes(base64.b64decode(block['data']))
  call('cad_clear_assembly_preview');assert call('cad_get_model')['sceneBodies']==saved['sceneBodies'];assert not call('cad_get_model')['project']['dirty']
  cancel=call('cad_check_assembly_path',{'bodyId':'plate','obstacleIds':['copy'],'stepMm':1,'waypoints':[{'x':0,'y':100,'z':0},{'x':200,'y':100,'z':0}]})
  cancelled=call('cad_cancel_assembly_job',{'jobId':cancel['jobId']});assert cancelled['status']=='cancelled',cancelled;assert call('cad_get_model')['sceneBodies']==saved['sceneBodies']
  instance=wait(call('cad_update_instance',{'id':'copy','rotationZ':'180 deg'}));assert call('cad_get_feature',{'id':'copy'})['feature']['rotationZ']=='180 deg';assert call('cad_get_model')['assemblyJob']['jobId']==instance['jobId']
  stale=call('cad_get_assembly_job',{'jobId':report['jobId']});assert stale['status']=='stale'
  comparison=wait(call('cad_compare_assembly',{'path':str(out/'assembly.kcad')}));assert any(v['bodyId']=='copy' for v in comparison['result']['changed'])
  path=wait(call('cad_check_assembly_path',{'bodyId':'plate','obstacleIds':['copy'],'stepMm':1,'waypoints':[{'x':0,'y':100,'z':0},{'x':2,'y':100,'z':0}]}));assert path['result']['continuousClearanceCertified']
  retention=wait(call('cad_check_retention',{'bodyId':'plate','obstacleIds':['copy'],'travelMm':1,'stepMm':1}));assert len(retention['result']['probes'])==6
  (out/'result.json').write_text(json.dumps({'passed':True,'application':initial['application'],'created':created,'updated':updated,'colors':colors,'cancelled':cancelled,'counts':counts,'manufacturing':report,'comparison':comparison,'path':path,'retention':retention},indent=2))
  print('ASSEMBLY_MCP_OK: batch/history/atomic failure/cache/instances/colors/persistence/manufacturing/highlights/exploded/cancel/compare/path/retention',initial['application'])
 finally:
  call('cad_open_project',{'path':str(restore),'discardChanges':True});print('Restored',restore)
