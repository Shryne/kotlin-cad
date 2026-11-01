#!/usr/bin/env python3
"""Exercise colors in an empty installed viewer, then reopen the supplied saved project."""
import sys,json,base64
from pathlib import Path
from mcp_client import CadClient
root=Path(__file__).resolve().parents[1]
out=root/'build/qa/object-colors';out.mkdir(parents=True,exist_ok=True)
restore=Path(sys.argv[1]).resolve();assert restore.is_file()
def value(result):
 return json.loads(next(i['text'] for i in result['content'] if i['type']=='text'))
with CadClient() as client:
 def call(name,args={}):return value(client.call(name,args))
 initial=call('cad_get_model');assert not initial['features'] and not initial.get('model') and not initial['project']['dirty']
 try:
  tools=client.request('tools/list',{})['tools']
  selected=[t for t in tools if t['name'] in ['cad_set_body_color','cad_get_color_regions','cad_set_area_color','cad_remove_area_color']];assert len(selected)==4
  (root/'docs/api/color-tools.schema.json').write_text(json.dumps({'tools':selected},indent=2)+'\n')
  a=call('cad_create_construction',{'name':'Blue body with orange top','shape':{'type':'box','x':20,'y':20,'z':20}})['bodyId']
  b=call('cad_create_construction',{'name':'Green body','shape':{'type':'box','x':15,'y':15,'z':12}})['bodyId']
  call('cad_set_body_position',{'id':b,'x':'30 mm'})
  call('cad_set_body_color',{'bodyId':a,'color':'#2266CC'});call('cad_set_body_color',{'bodyId':b,'color':'#33BB66'})
  regions=call('cad_get_color_regions',{'bodyId':a});top=next(r['id'] for r in regions['regions'] if r['normal']['z']>.9)
  area={'bodyId':a,'areaId':'lid','name':'Orange lid','color':'#FF8800','sourceRevision':regions['sourceRevision'],'regionIds':[top]}
  state=call('cad_set_area_color',area)
  def appearance(state,id):return next(b['appearance'] for b in state['sceneBodies'] if b['id']==id)
  assert appearance(state,a)['areas'][0]['name']=='Orange lid'
  before=state
  bad=client.request('tools/call',{'name':'cad_set_body_color','arguments':{'bodyId':a,'color':'invalid'}});assert bad['isError']
  assert call('cad_get_model')['sceneBodies']==before['sceneBodies']
  call('cad_remove_area_color',{'bodyId':a,'areaId':'lid'});assert not appearance(call('cad_get_model'),a)['areas']
  call('cad_undo');assert appearance(call('cad_get_model'),a)['areas'];call('cad_redo');assert not appearance(call('cad_get_model'),a)['areas'];call('cad_undo')
  call('cad_set_body_position',{'id':a,'x':'-5 mm'});assert not appearance(call('cad_get_model'),a)['staleAreaIds']
  call('cad_update_construction',{'id':a,'shape':{'type':'box','x':24,'y':20,'z':20}});assert appearance(call('cad_get_model'),a)['staleAreaIds']==['lid']
  bad=client.request('tools/call',{'name':'cad_set_area_color','arguments':area});assert bad['isError']
  regions=call('cad_get_color_regions',{'bodyId':a});area['sourceRevision']=regions['sourceRevision'];area['regionIds']=[next(r['id'] for r in regions['regions'] if r['normal']['z']>.9)];call('cad_set_area_color',area)
  saved=call('cad_save_project',{'path':str(out/'colors.kcad'),'overwrite':True});call('cad_open_project',{'path':str(out/'colors.kcad')})
  loaded=call('cad_get_model');assert loaded['sceneBodies']==saved['sceneBodies'];assert not loaded['project']['dirty']
  preview=client.call('cad_get_preview',{'maxSize':1200})
  for block in preview['content']:
   if block['type']=='image':(out/'colors.png').write_bytes(base64.b64decode(block['data']))
  (out/'result.json').write_text(json.dumps({'passed':True,'application':initial['application'],'model':loaded},indent=2))
  print('COLORS_MCP_OK: body/area/create/edit/remove/reject/translate/stale/undo/redo/save/load/preview',initial['application'])
 finally:
  call('cad_open_project',{'path':str(restore),'discardChanges':True});print('Restored',restore)
