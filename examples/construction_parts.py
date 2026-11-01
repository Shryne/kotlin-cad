#!/usr/bin/env python3
"""Create a slot/tongue demonstration in an EMPTY running CAD project."""
import json,sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'scripts'))
from mcp_client import CadClient

def value(result):return result.get('structuredContent') or json.loads(result['content'][0]['text'])
def box(x,y,z):return {'type':'box','x':x,'y':y,'z':z}
def move(shape,x,y,z):return {'type':'translate','shape':shape,'offset':[x,y,z]}
def plate_args():return {'name':'Slotted plate example','parameters':[{'name':'clearance','expression':'0.2 mm'}],
 'shape':{'type':'cut','a':box(30,20,4),'b':move(box({'expression':'8 mm + clearance'},10,6),10,5,-1)}}
def tongue_args():return {'name':'Mating tongue example','shape':move(box({'expression':'8 mm - clearance'},10,4),50,0,0)}
def main():
 with CadClient() as c:
  def call(name,args=None):return value(c.call(name,args or {}))
  state=call('cad_get_model')
  if state['features'] or state.get('model') or state['sketches'] or state['project']['dirty']:raise RuntimeError('Open an empty test project first; existing work will not be replaced')
  plate=call('cad_preview_construction',plate_args());assert plate['validation']['valid']
  plate=call('cad_create_construction',plate_args());tongue=call('cad_create_construction',tongue_args())
  call('cad_set_parameter',{'name':'clearance','expression':'0.5 mm'})
  edited=tongue_args();edited['id']=tongue['featureId'];edited['shape']['shape']['y']=12
  updated=call('cad_update_construction',edited);assert updated['bodyId']==tongue['bodyId']
  print(json.dumps({'plateId':plate['featureId'],'tongueId':updated['featureId'],'tongueVolumeMm3':updated['volumeMm3'],
   'interference':call('cad_check_interference',{'bodyIds':[plate['bodyId'],tongue['bodyId']]})},indent=2))
if __name__=='__main__':main()
