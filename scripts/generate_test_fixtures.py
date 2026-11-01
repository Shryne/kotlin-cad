#!/usr/bin/env python3
"""Generate disposable synthetic fixtures; never read or copy a user's CAD project."""
from pathlib import Path
import json,struct,zipfile
root=Path(__file__).resolve().parents[1];out=root/'samples';out.mkdir(exist_ok=True)
points=[(0.,0.,0.),(50.,0.,0.),(0.,40.,0.),(0.,0.,45.)]
indices=[0,2,1,0,1,3,0,3,2,1,2,3]
# Synthetic six-corner L profile extruded by 8 mm: 20 triangles.
outline=[(0.,0.),(60.,0.),(60.,12.),(12.,12.),(12.,50.),(0.,50.)]
bracket=[(x,y,z) for z in (0.,8.) for x,y in outline]
bracket_indices=[]
for a,b,c in [(0,1,2),(0,2,3),(0,3,5),(3,4,5)]:bracket_indices.extend([a,c,b,a+6,b+6,c+6])
for a in range(6):
 b=(a+1)%6;bracket_indices.extend([a,b,b+6,a,b+6,a+6])
mesh=struct.pack('>iii',0x4b434d31,len(bracket),len(bracket_indices))+b''.join(struct.pack('>ddd',*p) for p in bracket)+struct.pack('>'+str(len(bracket_indices))+'i',*bracket_indices)

def sketch(depth=None,mirror=None):
 return dict(id='synthetic-profile',name='Synthetic rectangle',plane='XY',shape='RECTANGLE',u='0 mm',v='0 mm',width='20 mm',height='10 mm',depth=depth,mirror=mirror,mirrorOffset='0 mm',angle='0 deg',centered=False,visible=True)
def project(name,sketches,with_mesh=False,version=9):
 data=dict(format='kotlin-cad',version=version,source=None,mesh={'name':'Synthetic bracket'} if with_mesh else None,display=dict(wireframe=False,grid=True,visible=True,unit='mm'),parameters=[],sketches=sketches,camera=dict(yaw=-35.,pitch=25.,panX=0.,panY=0.,zoom=1.))
 with zipfile.ZipFile(out/(name+'.kcad'),'w',compression=zipfile.ZIP_DEFLATED) as z:
  for filename,content in [('project.json',json.dumps(data,sort_keys=True).encode())]+([('mesh.bin',mesh)] if with_mesh else []):
   info=zipfile.ZipInfo(filename,(2020,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;z.writestr(info,content)
project('bracket',[],True,1)
project('sketches',[sketch()])
project('extrusions',[sketch('4 mm')])
project('mirrors',[sketch('4 mm','YZ')])
project('face-sketches',[sketch('4 mm')])
(out/'bracket.obj').write_text('# Synthetic L bracket test fixture\n'+''.join('v %g %g %g\n'%p for p in bracket)+''.join('f %d %d %d\n'%tuple(i+1 for i in bracket_indices[t:t+3]) for t in range(0,len(bracket_indices),3)))
lines=['solid synthetic']
for t in range(0,len(indices),3):
 lines+=['facet normal 0 0 0','outer loop']+['vertex %g %g %g'%points[i] for i in indices[t:t+3]]+['endloop','endfacet']
(out/'tetrahedron.stl').write_text('\n'.join(lines+['endsolid synthetic'])+'\n')
print('SYNTHETIC_FIXTURES_OK')
