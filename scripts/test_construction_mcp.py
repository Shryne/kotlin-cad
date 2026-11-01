#!/usr/bin/env python3
"""Test installed construction API in an already running empty canonical viewer, then restore a saved project."""
import json,sys,zipfile
from pathlib import Path
from mcp_client import CadClient
root=Path(__file__).resolve().parents[1];sys.path.insert(0,str(root/'examples'))
from construction_parts import plate_args,tongue_args,box,value
restore=Path(sys.argv[1]).resolve();assert restore.is_file()
out=root/'build/qa/construction-api';out.mkdir(parents=True,exist_ok=True)
with CadClient() as client:
 def call(name,args=None):return value(client.call(name,args or {}))
 def fails(name,args):
  r=client.request('tools/call',{'name':name,'arguments':args});assert r.get('isError'),r
 owned=False
 try:
  initial=call('cad_get_model');assert not initial['features'] and not initial['project']['dirty'] and not initial.get('model')
  owned=True
  names={t['name'] for t in client.request('tools/list',{})['tools']}
  assert {'cad_create_construction','cad_preview_construction','cad_update_construction','cad_delete_feature','cad_check_interference','cad_export_bodies'}<=names
  preview=call('cad_preview_construction',plate_args());assert preview['validation']['valid'];unchanged=call('cad_get_model');assert not unchanged['features'] and not unchanged['parameters'] and not unchanged['project']['dirty']
  plate=call('cad_create_construction',plate_args());tongue=call('cad_create_construction',tongue_args());a=plate['featureId'];b=tongue['featureId']
  assert abs(tongue['volumeMm3']-312)<1e-5
  call('cad_set_parameter',{'name':'clearance','expression':'0.5 mm'})
  assert abs(call('cad_get_feature',{'id':b})['volumeMm3']-300)<1e-5
  assert abs(call('cad_get_feature',{'id':a})['volumeMm3']-2060)<1e-5
  edges=call('cad_inspect_construction',{'shape':tongue_args()['shape']})['edges'];assert edges
  update=tongue_args();update['id']=b;update['shape']['shape']['y']=12
  edited=call('cad_update_construction',update);assert edited['bodyId']==b and abs(edited['volumeMm3']-360)<1e-5
  fails('cad_update_construction',{'id':b,'shape':box(-1,10,10)})
  fails('cad_set_parameter',{'name':'clearance','expression':'20 mm'})
  assert call('cad_get_feature',{'id':b})['feature']==edited['feature']
  assert call('cad_get_parameters')['parameters'][0]['expression']=='0.5 mm'
  call('cad_undo');assert abs(call('cad_get_feature',{'id':b})['volumeMm3']-300)<1e-5
  call('cad_redo');assert abs(call('cad_get_feature',{'id':b})['volumeMm3']-360)<1e-5
  call('cad_save_project',{'path':str(out/'parts.kcad'),'overwrite':True});call('cad_open_project',{'path':str(out/'parts.kcad')})
  assert call('cad_get_feature',{'id':b})['feature']==edited['feature']
  state=call('cad_get_model')
  for ext in ['stl','obj']:
   export=call('cad_export_bodies',{'bodyIds':[b],'path':str(out/('tongue-flat.'+ext)),'overwrite':True,'orientation':{'rotationX':'90 deg','placeOnBed':True}})
   assert abs(export['bodies'][0]['bounds']['min']['z'])<1e-6 and abs(export['bodies'][0]['dimensions']['z']-12)<1e-5
  after=call('cad_get_model');assert after['features']==state['features'] and after['project']==state['project'] and after['sceneBodies']==state['sceneBodies']
  call('cad_delete_feature',{'id':b});assert not any(f['id']==b for f in call('cad_get_model')['features']);call('cad_undo');assert call('cad_get_feature',{'id':b})['feature']==edited['feature']
  c=call('cad_create_construction',{'name':'Collision A','shape':box(10,10,10)})['bodyId']
  d=call('cad_create_construction',{'name':'Collision B','shape':box(10,10,10)})['bodyId']
  checks=[]
  for x,status,volume in [('12 mm','separated',0),('10 mm','contact',0),('8 mm','overlap',200)]:
   call('cad_set_body_position',{'id':d,'x':x});r=call('cad_check_interference',{'bodyIds':[c,d]})['pairs'][0]
   assert r['status']==status and abs(r['intersectionVolumeMm3']-volume)<1e-5;checks.append(r)
  (out/'mcp-result.json').write_text(json.dumps({'application':initial['application'],'preview':preview,'created':plate,'updated':edited,'interference':checks,'export':export,'passed':True},indent=2))
  print('CONSTRUCTION_MCP_OK: preview/create/parameters/inspect/update/reject/history/save/load/delete/interference/flat STL+OBJ',initial['application'])
 finally:
  if owned:
   # Only the fixture session is discarded. The supplied saved user project is read, never overwritten.
   restored=call('cad_open_project',{'path':str(restore),'discardChanges':True});print('Restored:',restored['project']['path'],'bodies:',len(restored['sceneBodies']))
