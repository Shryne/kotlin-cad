#!/usr/bin/env python3
"""End-to-end check against an already-running viewer; no third-party Python packages."""
import base64
import json
import math
import os
from pathlib import Path
import selectors
import struct
import shutil
import tempfile
import subprocess
import sys
import time

root = Path(__file__).resolve().parents[1]
command = sys.argv[1:] or [str(root / 'build/install/kotlin-cad/bin/kotlin-cad'), '--mcp']
log = (root / 'build/qa/mcp-stderr.log')
log.parent.mkdir(parents=True, exist_ok=True)
with log.open('w') as stderr:
    p = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=stderr)
    selector = selectors.DefaultSelector()
    selector.register(p.stdout, selectors.EVENT_READ)
    counter = 0

    def notify(method, params):
        p.stdin.write((json.dumps({'jsonrpc': '2.0', 'method': method, 'params': params}) + '\n').encode())
        p.stdin.flush()

    def request(method, params=None):
        global counter
        counter += 1
        p.stdin.write((json.dumps({'jsonrpc': '2.0', 'id': counter, 'method': method, 'params': params or {}}) + '\n').encode())
        p.stdin.flush()
        while True:
            assert selector.select(timeout=30), f'Timeout waiting for {method}'
            line = p.stdout.readline()
            assert line, f'MCP process exited: {log.read_text()}'
            response = json.loads(line)
            if response.get('id') == counter:
                assert 'error' not in response, response
                return response['result']

    def tool(name, arguments=None, error=False):
        result = request('tools/call', {'name': name, 'arguments': arguments or {}})
        assert bool(result.get('isError')) == error, result
        if error:
            return result
        if name == 'cad_get_preview':
            return result['content'][0]
        return result.get('structuredContent') or json.loads(result['content'][0]['text'])

    def make_monitor_reference(path, ambiguous=False):
        """Build the exact synthetic plate through the bundled local OCCT worker."""
        kernel = Path(os.environ.get('KOTLIN_CAD_KERNEL', root / 'build/native-runtime/cad-kernel'))
        box = lambda x,y,z: {'type':'box','x':x,'y':y,'z':z}
        def translate(shape,x,y,z): return {'type':'translate','shape':shape,'offset':[x,y,z]}
        def cylinder(radius,height,x,y,z): return translate({'type':'cylinder','radius':radius,'height':height},x,y,z)
        if ambiguous:
            cutter=cylinder(10,8,40,40,-1)
            parts=[cylinder(10,8,80,40,-1)]
        else:
            cutter=cylinder(10,8,60,52,-1)
            parts=[translate(box(20,10,8),50,20,-1),cylinder(5,8,50,25,-1),cylinder(5,8,70,25,-1)]
        parts += [cylinder(4,8,x,y,-1) for x,y in [(15,15),(105,15),(15,65),(105,65)]]
        for part in parts:
            cutter={'type':'union','a':cutter,'b':part}
        request={'id':1,'operation':'evaluate','shape':{'type':'cut','a':box(120,80,6),'b':cutter}}
        result=subprocess.run([str(kernel)],input=json.dumps(request)+'\n',text=True,capture_output=True,check=True,timeout=60)
        mesh=json.loads(result.stdout.splitlines()[0])['result']
        vertices=mesh['vertices'];indices=mesh['indices'];triangles=[]
        for i in range(0,len(indices),3):
            a,b,c=(vertices[indices[i+j]] for j in range(3))
            ab=[b[k]-a[k] for k in range(3)];ac=[c[k]-a[k] for k in range(3)]
            n=[ab[1]*ac[2]-ab[2]*ac[1],ab[2]*ac[0]-ab[0]*ac[2],ab[0]*ac[1]-ab[1]*ac[0]]
            length=math.sqrt(sum(v*v for v in n));normal=[v/length for v in n] if length else [0,0,0]
            triangles.append(struct.pack('<12fH',*(normal+ a+b+c),0))
        with path.open('wb') as stl:
            stl.write(b'Kotlin CAD monitor reference'.ljust(80,b' '));stl.write(struct.pack('<I',len(triangles)));stl.writelines(triangles)
        return len(triangles)

    def make_squat_rack_reference(path):
        """Build a fused hollow-profile rack with a known 400 mm hole pitch."""
        kernel = Path(os.environ.get('KOTLIN_CAD_KERNEL', root / 'build/native-runtime/cad-kernel'))
        box=lambda x,y,z:{'type':'box','x':x,'y':y,'z':z}
        def translate(shape,x,y,z): return {'type':'translate','shape':shape,'offset':[x,y,z]}
        def combine(kind,a,b): return {'type':kind,'a':a,'b':b}
        def ring_tool(x,y,z,radius,length):
            outer=[[x,y+radius*math.cos(i*2*math.pi/32),z+radius*math.sin(i*2*math.pi/32)] for i in range(32)]
            return {'type':'extrude','outer':outer,'vector':[length,0,0]}
        def post(x):
            shape=combine('cut',box(60,60,2192),translate(box(54,54,2194),3,3,-1))
            for z in (400,800,1200,1600):
                shape=combine('cut',shape,ring_tool(-1,30,z,6,5))
                shape=combine('cut',shape,ring_tool(56,30,z,6,5))
            return translate(shape,x,0,8)
        def plate(x):
            shape=translate(box(150,100,8),x,-20,0)
            for dx,dy in ((40,25),(110,25),(40,75),(110,75)):
                tool=translate({'type':'cylinder','radius':6,'height':10},x+dx,-20+dy,-1)
                shape=combine('cut',shape,tool)
            return shape
        beam=combine('cut',box(940,60,60),translate(box(942,54,54),-1,3,3))
        assembly=post(0)
        for shape in (post(940),translate(beam,30,0,2140),plate(-45),plate(895)):
            assembly=combine('union',assembly,shape)
        request={'id':1,'operation':'evaluate','shape':assembly}
        result=subprocess.run([str(kernel)],input=json.dumps(request)+'\n',text=True,capture_output=True,check=True,timeout=120)
        mesh=json.loads(result.stdout.splitlines()[0])['result'];vertices=mesh['vertices'];indices=mesh['indices'];triangles=[]
        for i in range(0,len(indices),3):
            a,b,c=(vertices[indices[i+j]] for j in range(3));ab=[b[k]-a[k] for k in range(3)];ac=[c[k]-a[k] for k in range(3)]
            n=[ab[1]*ac[2]-ab[2]*ac[1],ab[2]*ac[0]-ab[0]*ac[2],ab[0]*ac[1]-ab[1]*ac[0]]
            length=math.sqrt(sum(v*v for v in n));normal=[v/length for v in n] if length else [0,0,0]
            triangles.append(struct.pack('<12fH',*(normal+a+b+c),0))
        with path.open('wb') as stl:
            stl.write(b'Kotlin CAD fused rack reference'.ljust(80,b' '));stl.write(struct.pack('<I',len(triangles)));stl.writelines(triangles)
        return len(triangles)

    try:
        initialized = request('initialize', {'protocolVersion': '2025-06-18', 'capabilities': {}, 'clientInfo': {'name': 'cad-e2e', 'version': '1'}})
        assert initialized['serverInfo']['name'] == 'kotlin-cad'
        notify('notifications/initialized', {})
        tools = request('tools/list')['tools']
        assert len(tools) >= 35, tools
        tool('cad_get_model')
        box_preview=tool('cad_preview_primitive',{'kind':'BOX','width':'30 mm','depth':'20 mm','height':'10 mm','x':'5 mm'})
        assert box_preview['preview']['dimensions']=={'x':30.0,'y':20.0,'z':10.0}
        assert box_preview['preview']['bounds']['min']['x']==5.0
        for kind,dimensions in [('CYLINDER',{'x':12.0,'y':12.0,'z':12.0}),('SPHERE',{'x':12.0,'y':12.0,'z':12.0})]:
            preview=tool('cad_preview_primitive',{'kind':kind,'radius':'6 mm','height':'12 mm'})
            assert preview['preview']['triangles']>0
            for axis,value in dimensions.items(): assert abs(preview['preview']['dimensions'][axis]-value)<0.1, (kind,axis,preview['preview']['dimensions'])
        created=tool('cad_create_primitive',{'kind':'BOX','name':'Testblock','width':'30 mm','depth':'20 mm','height':'10 mm'})
        assert created['features'][-1]['kind']=='BOX' and created['sceneBodies'][-1]['name']=='Testblock'
        undonePrimitive=tool('cad_undo');assert not any(f.get('name')=='Testblock' for f in undonePrimitive['features']), undonePrimitive['features']
        unicode=tool('cad_set_parameter',{'name':'Öffnung','expression':'20 mm'})
        unicode=tool('cad_set_parameter',{'name':'ho\u0308he','expression':'Öffnung + 5 mm'})
        assert next(p for p in unicode['parameters'] if p['name']=='höhe')['value']==25
        tool('cad_undo');tool('cad_undo')
        model = tool('cad_open_file', {'path': str(root / 'samples/tetrahedron.stl'),'discardChanges':True})
        assert model['model']['triangles'] == 4, model
        assert model['model']['dimensions'] == {'x': 50.0, 'y': 40.0, 'z': 45.0}
        removed=tool('cad_delete_import')
        assert removed['model'] is None and removed['sceneBodies']==[]
        tool('cad_delete_import',error=True)
        assert tool('cad_undo')['model']['triangles']==4
        assert tool('cad_redo')['model'] is None
        assert tool('cad_undo')['model']['triangles']==4
        tool('cad_open_file', {'path': '/nonexistent/missing.stl'}, error=True)
        assert tool('cad_get_model')['model']['triangles'] == 4
        assert tool('cad_set_view', {'view': 'top', 'fit': True})['view'] == 'Oben'
        display = tool('cad_set_display', {'wireframe': True, 'grid': False, 'visible': False, 'unit': 'cm', 'hiddenBodyIds': []})['display']
        assert display == {'wireframe': False, 'grid': False, 'visible': False, 'unit': 'cm', 'hiddenBodyIds': []}, display
        tool('cad_set_display', {'unit': 'invalid'}, error=True)
        tool('cad_set_display', {'wireframe': 'true'}, error=True)
        tool('cad_navigate', {'zoom': 0}, error=True)
        tool('cad_navigate', {'yaw': 20, 'pitch': -10, 'panX': 10, 'panY': -5, 'zoom': 1.2})
        tool('cad_set_display', {'visible': True})
        preview = tool('cad_get_preview', {'maxSize': 800})
        assert preview['type'] == 'image' and preview['mimeType'] == 'image/png', preview.keys()
        png = base64.b64decode(preview['data'])
        assert png.startswith(b'\x89PNG\r\n\x1a\n')
        width, height = struct.unpack('>II', png[16:24])
        assert 0 < width <= 800 and 0 < height <= 800, (width, height)
        (root / 'build/qa/mcp-preview.png').write_bytes(png)
        tool('cad_get_preview', {'maxSize': 99999}, error=True)
        tool('cad_set_display', {'wireframe': False, 'grid': True, 'visible': True, 'unit': 'mm'})
        tool('cad_open_file', {'path': str(root / 'samples/bracket.obj'), 'discardChanges': True})
        tool('cad_set_view', {'view': 'iso', 'fit': True})
        # Parameter dependencies, transactional validation, project portability and history.
        with tempfile.TemporaryDirectory(dir=root / 'build/qa', prefix='project-e2e-') as temporary:
            folder = Path(temporary)
            source = folder / 'source.obj'
            shutil.copy2(root / 'samples/bracket.obj', source)
            tool('cad_open_file', {'path': str(source), 'discardChanges': True})
            tool('cad_set_parameter', {'name': 'width', 'expression': '60 mm'})
            values = tool('cad_set_parameter', {'name': 'half', 'expression': 'width / 2'})
            assert values['parameters'][1]['value'] == 30.0
            tool('cad_set_parameter', {'name': 'width', 'expression': '80 mm'})
            assert tool('cad_get_parameters')['parameters'][1]['value'] == 40.0
            assert tool('cad_undo')['parameters'][1]['value'] == 30.0
            assert tool('cad_redo')['parameters'][1]['value'] == 40.0
            tool('cad_set_parameter', {'name': 'width', 'expression': 'half + 1 mm'}, error=True)
            assert tool('cad_get_parameters')['parameters'][0]['expression'] == '80 mm'
            tool('cad_delete_parameter', {'name': 'width'}, error=True)
            tool('cad_delete_parameter', {'name': 'half'})
            assert len(tool('cad_undo')['parameters']) == 2
            tool('cad_set_view', {'view': 'top', 'fit': True})
            tool('cad_set_display', {'grid': False, 'unit': 'cm'})
            profile = dict(name='Parametric rectangle', plane='XY', shape='RECTANGLE', u='0 mm', v='0 mm', width='width', height='half')
            result = tool('cad_set_sketch', profile)
            sketch_id = result['sketches'][0]['id']
            canonical=tool('cad_get_sketch',{'id':sketch_id})
            assert canonical['sketch']['depth'] is None and canonical['sketch']['elements'][0]['id']=='profile'
            assert canonical['regions'][0]['id']=='profile'
            assert result['schemaVersion']==10
            # Shared solver diagnosis, atomic failures, history and persisted constraints.
            circle_id='constraint-e2e'
            tool('cad_set_sketch',dict(id=circle_id,name='Constraint test',plane='XY',shape='CIRCLE',u='0 mm',v='0 mm',width='5 mm'))
            accepted=tool('cad_add_sketch_constraint',{'sketchId':circle_id,'type':'RADIUS','elementIds':['profile'],'value':'8 mm'})
            assert accepted['accepted'] and accepted['constraintStatus']=='underconstrained'
            checked=tool('cad_get_sketch',{'id':circle_id})
            assert checked['constraintStatus']=='underconstrained' and checked['sketch']['constraints'][0]['value']=='8 mm'
            conflict=tool('cad_add_sketch_constraint',{'sketchId':circle_id,'type':'RADIUS','elementIds':['profile'],'value':'9 mm'},error=True)
            diagnostic=conflict['structuredContent']
            assert diagnostic['accepted'] is False and diagnostic['constraintStatus']=='conflicting' and diagnostic['conflicts']
            assert len(tool('cad_get_sketch',{'id':circle_id})['sketch']['constraints'])==1
            assert tool('cad_undo')['sketches'][1]['constraints']==[]
            redone=tool('cad_redo')
            redone_circle=next(item for item in redone['sketches'] if item['id']==circle_id)
            assert abs(float(redone_circle['elements'][0]['width'].split()[0])-8.0)<1e-5
            with tempfile.TemporaryDirectory(dir=root / 'build/qa',prefix='constraint-e2e-') as temporary:
                path=str(Path(temporary)/'constraints.kcad')
                tool('cad_save_project',{'path':path})
                restored=tool('cad_open_project',{'path':path})
                sketch=next(item for item in restored['sketches'] if item['id']==circle_id)
                assert sketch['constraints'][0]['value']=='8 mm'
                assert abs(float(sketch['elements'][0]['width'].split()[0])-8.0)<1e-5
                assert tool('cad_get_sketch',{'id':circle_id})['degreesOfFreedom']==2
                removed=tool('cad_remove_sketch_constraint',{'sketchId':circle_id,'constraintId':sketch['constraints'][0]['id']})
                assert not next(item for item in removed['sketches'] if item['id']==circle_id)['constraints']
                assert tool('cad_undo')['sketches'][1]['constraints']
                assert not tool('cad_redo')['sketches'][1]['constraints']
            tool('cad_delete_sketch',{'id':circle_id})
            for feature in result['features']:
                detail=tool('cad_get_feature',{'id':feature['id']})
                assert detail['feature']==feature and detail['faces']
            tool('cad_get_feature',{'id':'does-not-exist'},error=True)
            tool('cad_set_parameter', {'name': 'half', 'expression': '2 deg'}, error=True)
            tool('cad_delete_parameter', {'name': 'half'}, error=True)
            tool('cad_set_sketch', dict(profile, width='-1 mm'), error=True)
            assert len(tool('cad_get_model')['sketches']) == 1
            tool('cad_delete_sketch', {'id': sketch_id})
            assert not tool('cad_get_model')['sketches']
            assert len(tool('cad_undo')['sketches']) == 1
            extruded = tool('cad_set_sketch', dict(profile, id=sketch_id, depth='half / 2'))
            assert extruded['bodies'][0]['dimensions']['z'] == 20
            assert extruded['bodies'][0]['bounds']=={'min':{'x':0,'y':0,'z':0},'max':{'x':80,'y':40,'z':20}}
            sketch_body=next(body for body in extruded['sceneBodies'] if body['id']==f'sketch:{sketch_id}')
            assert sketch_body['bounds']==extruded['bodies'][0]['bounds']
            extrusion_id=next(feature['id'] for feature in extruded['features'] if feature.get('sketchId')==sketch_id)
            moved=tool('cad_set_body_position',{'id':extrusion_id,'x':'width / 2','y':'-3 mm','z':'2 mm'})
            moved_feature=next(feature for feature in moved['features'] if feature['id']==extrusion_id)
            assert moved_feature['x']=='width / 2' and moved_feature['y']=='-3 mm' and moved_feature['z']=='2 mm'
            moved_body=next(body for body in moved['sceneBodies'] if body['id']==extrusion_id)
            assert moved_body['bounds']['min']=={'x':40,'y':-3,'z':2}
            restored_move=tool('cad_undo')
            restored_body=next(body for body in restored_move['sceneBodies'] if body['id']==extrusion_id)
            assert restored_body['bounds']['min']=={'x':0,'y':0,'z':0}
            replayed_move=tool('cad_redo')
            assert next(body for body in replayed_move['sceneBodies'] if body['id']==extrusion_id)['bounds']['min']=={'x':40,'y':-3,'z':2}
            tool('cad_set_sketch', dict(profile, id=sketch_id, depth='-half / 2'))
            assert tool('cad_get_model')['bodies'][0]['dimensions']['z'] == 20
            tool('cad_undo')
            assert tool('cad_get_model')['bodies'][0]['dimensions']['z'] == 20
            tool('cad_set_sketch', dict(profile, id=sketch_id, depth='0 mm'), error=True)
            mirrored = dict(profile, id=sketch_id, depth='half / 2', mirror='YZ', mirrorOffset='-width / 4')
            assert len(tool('cad_set_sketch', mirrored)['bodies']) == 2
            assert len(tool('cad_undo')['bodies']) == 1
            assert len(tool('cad_redo')['bodies']) == 2
            tool('cad_set_sketch', dict(mirrored, mirrorOffset='2 deg'), error=True)
            assert len(tool('cad_get_model')['bodies']) == 2
            project = folder / 'roundtrip.kcad'   
            saved = tool('cad_save_project', {'path': str(project)})
            assert project.is_file() and not saved['project']['dirty']
            source.unlink()
            tool('cad_save_project', {'path': str(project)}, error=True)
            tool('cad_set_parameter', {'name': 'width', 'expression': '100 mm'})
            assert tool('cad_get_model')['project']['dirty']
            assert all(body['dimensions']['x'] == 100 for body in tool('cad_get_model')['bodies'])
            assert not tool('cad_undo')['project']['dirty']
            assert tool('cad_redo')['project']['dirty']
            tool('cad_open_project', {'path': str(project)}, error=True)
            tool('cad_open_file', {'path': str(root / 'samples/tetrahedron.stl')}, error=True)
            restored = tool('cad_open_project', {'path': str(project), 'discardChanges': True})
            assert restored['parameters'][0]['value'] == 80.0
            assert restored['parameters'][1]['value'] == 40.0
            assert restored['sketches'][0]['width'] == 'width'
            assert restored['sketches'][0]['mirror'] == 'YZ'
            assert restored['sketches'][0]['mirrorOffset'] == '-width / 4'
            assert len(restored['bodies']) == 2
            assert restored['bodies'][0]['dimensions']['z'] == 20
            assert restored['model']['triangles'] == 20
            assert restored['display']['unit'] == 'cm' and not restored['display']['grid']
            assert restored['view'] == 'Oben'
            assert not restored['project']['dirty'] and not restored['project']['canUndo']
            tool('cad_open_file', {'path': str(root / 'samples/bracket.obj'), 'discardChanges': True})
        # Attach a sketch to a body face and preserve that reference through edits and save/load.
        tool('cad_set_parameter', {'name': 'height', 'expression': '12 mm'})
        parent = tool('cad_set_sketch', dict(name='Parent', plane='XY', shape='RECTANGLE', u='0 mm', v='0 mm', width='60 mm', height='40 mm', depth='height'))
        parent_id = parent['sketches'][0]['id']
        # Imported bracket has 20 triangles; cap triangle 1 belongs to the parent body.
        child_definition = dict(name='On top face', plane='XY', shape='RECTANGLE', u='5 mm', v='5 mm', width='10 mm', height='10 mm', depth='3 mm', supportFace=21)
        attached = tool('cad_set_sketch', child_definition)
        child_id = attached['sketches'][1]['id']
        assert attached['sketches'][1]['support']['sketchId'] == parent_id
        assert attached['sketches'][1]['frame']['origin'][2] == 12
        changed = tool('cad_set_parameter', {'name': 'height', 'expression': '25 mm'})
        assert changed['sketches'][1]['frame']['origin'][2] == 25
        tool('cad_delete_sketch', {'id': parent_id}, error=True)
        tool('cad_set_sketch', dict(child_definition, id=child_id, supportFace=999999), error=True)
        with tempfile.TemporaryDirectory(dir=root / 'build/qa', prefix='support-e2e-') as temporary:
            target = str(Path(temporary) / 'support.kcad')
            tool('cad_save_project', {'path': target})
            restored = tool('cad_open_project', {'path': target})
            assert restored['sketches'][1]['frame']['origin'][2] == 25
            assert restored['sketches'][1]['support']['triangle'] == 1
        tool('cad_open_file', {'path': str(root / 'samples/bracket.obj'), 'discardChanges': True})
        # Parametric in-plane rotation, validation and history.
        tool('cad_set_parameter', {'name': 'turn', 'expression': '90 deg'})
        rotated = tool('cad_set_sketch', dict(id='rotated-qa', name='Rotated', plane='XY', shape='RECTANGLE', u='0 mm', v='0 mm', width='40 mm', height='10 mm', depth='6 mm', angle='turn'))
        assert rotated['sketches'][0]['angle'] == 'turn'
        assert abs(rotated['bodies'][0]['dimensions']['x'] - 10) < 1e-6
        tool('cad_set_parameter', {'name': 'turn', 'expression': '0 deg'})
        assert abs(tool('cad_get_model')['bodies'][0]['dimensions']['x'] - 40) < 1e-6
        tool('cad_undo')
        assert abs(tool('cad_get_model')['bodies'][0]['dimensions']['x'] - 10) < 1e-6
        tool('cad_set_parameter', {'name': 'turn', 'expression': '10 mm'}, error=True)
        tool('cad_open_file', {'path': str(root / 'samples/bracket.obj'), 'discardChanges': True})
        # Center-anchored rectangles keep their profile type and center across save/load.
        tool('cad_set_parameter', {'name':'span','expression':'40 mm'})
        centered = tool('cad_set_sketch', dict(id='center-qa',name='Centered',plane='XY',shape='RECTANGLE',u='25 mm',v='35 mm',width='span',height='20 mm',depth='6 mm',centered=True))
        assert centered['sketches'][0]['centered']
        changed = tool('cad_set_parameter', {'name':'span','expression':'60 mm'})
        assert changed['sketches'][0]['u']=='25 mm' and changed['sketches'][0]['v']=='35 mm'
        with tempfile.TemporaryDirectory(dir=root / 'build/qa', prefix='center-e2e-') as temporary:
            path=str(Path(temporary)/'center.kcad')
            tool('cad_save_project',{'path':path})
            restored=tool('cad_open_project',{'path':path})
            assert restored['sketches'][0]['centered'] and restored['sketches'][0]['width']=='span'
        tool('cad_set_sketch',dict(name='Invalid center',plane='XY',shape='CIRCLE',u='0 mm',v='0 mm',width='10 mm',centered=True),error=True)
        tool('cad_open_file', {'path': str(root / 'samples/bracket.obj'), 'discardChanges': True})
        # Hiding a sketch contour preserves the solid and supports undo.
        visibility = tool('cad_set_sketch',dict(id='visibility-qa',name='Visibility',plane='XY',shape='RECTANGLE',u='0 mm',v='0 mm',width='20 mm',height='10 mm',depth='5 mm'))
        hidden=tool('cad_set_sketch_visibility',{'id':'visibility-qa','visible':False})
        assert hidden['sketches'][0]['visible'] is False and hidden['bodies']==visibility['bodies']
        assert tool('cad_undo')['sketches'][0]['visible'] is True
        assert tool('cad_redo')['sketches'][0]['visible'] is False
        renamed=tool('cad_rename_sketch',{'id':'visibility-qa','name':'  Fenster  '})
        assert renamed['sketches'][0]['name']=='Fenster'
        tool('cad_rename_sketch',{'id':'visibility-qa','name':' '},error=True)
        assert tool('cad_get_model')['sketches']==renamed['sketches']
        duplicate=tool('cad_duplicate_sketch',{'id':'visibility-qa'})
        assert len(duplicate['sketches'])==2 and duplicate['bodies']==renamed['bodies']
        copied=duplicate['sketches'][-1]
        assert copied['name']=='Fenster Kopie' and copied['id']!='visibility-qa' and copied['depth'] is None and copied['visible']
        assert tool('cad_undo')['sketches']==renamed['sketches']
        assert tool('cad_redo')['sketches']==duplicate['sketches']
        with tempfile.TemporaryDirectory(dir=root / 'build/qa',prefix='duplicate-e2e-') as temporary:
            path=str(Path(temporary)/'copy.kcad')
            tool('cad_save_project',{'path':path})
            restored=tool('cad_open_project',{'path':path})
            assert restored['sketches']==duplicate['sketches']
        tool('cad_open_file', {'path': str(root / 'samples/bracket.obj'), 'discardChanges': True})
        # Body visibility is independent of the sketch and keeps face indices and exports valid.
        tool('cad_set_sketch',dict(id='body-qa',name='Body',plane='XY',shape='RECTANGLE',u='100 mm',v='0 mm',width='20 mm',height='10 mm',depth='5 mm',mirror='YZ'))
        before=tool('cad_get_model')
        hidden=tool('cad_set_body_visibility',{'id':'import','visible':False})
        hidden=tool('cad_set_body_visibility',{'id':'mirror:body-qa','visible':False})
        assert [b['visible'] for b in hidden['sceneBodies']]==[False,True,False]
        assert hidden['bodies']==before['bodies'] and hidden['sketches']==before['sketches']
        tool('cad_set_body_visibility',{'id':'missing','visible':False},error=True)
        assert tool('cad_undo')['sceneBodies'][-1]['visible']
        assert not tool('cad_redo')['sceneBodies'][-1]['visible']
        wire=tool('cad_set_display',{'wireframe':True})
        assert not wire['display']['wireframe'] and wire['warnings']
        assert wire['display']['hiddenBodyIds']==['import','mirror:body-qa']
        with tempfile.TemporaryDirectory(dir=root / 'build/qa',prefix='body-visible-') as temporary:
            path=str(Path(temporary)/'hidden.kcad')
            tool('cad_save_project',{'path':path})
            restored=tool('cad_open_project',{'path':path})
            assert restored['sceneBodies']==hidden['sceneBodies']
            exported=tool('cad_export_mesh',{'path':str(Path(temporary)/'all.stl')})
            assert exported['parts']==3
        before_fit=tool('cad_get_model')
        tool('cad_fit_body',{'id':'mirror:body-qa'},error=True)
        fitted=tool('cad_fit_body',{'id':'sketch:body-qa'})
        assert fitted['project']==before_fit['project'] and fitted['sceneBodies']==before_fit['sceneBodies']
        assert fitted['sketches']==before_fit['sketches'] and fitted['bodies']==before_fit['bodies']
        assert fitted['camera']['zoom']>1
        fitted_again=tool('cad_fit_body',{'id':'sketch:body-qa'})
        assert fitted_again['camera']==fitted['camera']
        tool('cad_fit_body',{'id':'missing'},error=True)
        isolated=tool('cad_isolate_body',{'id':'mirror:body-qa'})
        assert [b['effectiveVisible'] for b in isolated['sceneBodies']]==[False,False,True]
        assert isolated['sketches']==hidden['sketches']
        tool('cad_isolate_body',{'id':'mirror:body-qa'})
        assert tool('cad_undo')['sceneBodies']==hidden['sceneBodies']
        assert tool('cad_redo')['sceneBodies']==isolated['sceneBodies']
        tool('cad_isolate_body',{'id':'missing'},error=True)
        all_bodies=tool('cad_show_all_bodies')
        assert all(b['effectiveVisible'] for b in all_bodies['sceneBodies'])
        tool('cad_show_all_bodies')
        assert tool('cad_undo')['sceneBodies']==isolated['sceneBodies']
        assert tool('cad_redo')['sceneBodies']==all_bodies['sceneBodies']
        tool('cad_open_file', {'path': str(root / 'samples/bracket.obj'), 'discardChanges': True})
        # Export committed geometry without changing the project, history or view.
        with tempfile.TemporaryDirectory(dir=root / 'build/qa', prefix='export-e2e-') as temporary:
            folder = Path(temporary)
            tool('cad_set_parameter', {'name': 'depth', 'expression': '12 mm'})
            tool('cad_set_sketch', dict(name='Export body', plane='XY', shape='RECTANGLE', u='0 mm', v='0 mm', width='20 mm', height='10 mm', depth='depth', mirror='YZ'))
            before = tool('cad_get_model')
            stl, obj = folder / 'assembly.stl', folder / 'assembly.obj'
            exported = tool('cad_export_mesh', {'path': str(stl)})
            assert exported['parts'] == 3 and exported['triangles'] == 44
            assert stl.stat().st_size == 84 + 50 * 44
            assert struct.unpack('<I', stl.read_bytes()[80:84])[0] == 44
            tool('cad_export_mesh', {'path': str(stl)}, error=True)
            assert tool('cad_export_mesh', {'path': str(stl), 'overwrite': True})['triangles'] == 44
            assert tool('cad_export_mesh', {'path': str(obj)})['format'] == 'OBJ'
            assert obj.read_text().count('\no part_') == 3
            tool('cad_export_mesh', {'path': str(folder / 'part.step')}, error=True)
            tool('cad_export_mesh', {'path': 'relative.stl'}, error=True)
            after = tool('cad_get_model')
            assert after['project'] == before['project']
            assert after['view'] == before['view'] and after['parameters'] == before['parameters'] and after['sketches'] == before['sketches']
            assert not tool('cad_open_file', {'path': str(stl), 'discardChanges': True})['sketches']
            assert tool('cad_get_model')['model']['triangles'] == 44
            assert tool('cad_open_file', {'path': str(obj)})['model']['triangles'] == 44
        with tempfile.TemporaryDirectory(dir=root/'build/qa',prefix='multi-elements-') as temporary:
            project=Path(temporary)/'multi.kcad'
            from zipfile import ZipFile
            first=dict(id='plate',shape='RECTANGLE',u='0 mm',v='0 mm',width='20 mm',height='10 mm',angle='0 deg',centered=False)
            second=dict(first,id='other',u='50 mm')
            sketch=dict(first,id='multi',name='Multi',plane='XY',depth=None,mirror=None,mirrorOffset='0 mm',frame=None,support=None,visible=True,elements=[first,second])
            metadata=dict(format='kotlin-cad',version=10,sketches=[sketch],features=[dict(type='extrusion',id='body',sketchId='multi',regionId='plate',depth='5 mm')],
                source=None,mesh=None,parameters=[],display=dict(wireframe=False,grid=True,visible=True,unit='mm',hiddenBodyIds=[]),camera=dict(yaw=-35,pitch=25,panX=0,panY=0,zoom=1))
            with ZipFile(project,'w') as archive:archive.writestr('project.json',json.dumps(metadata))
            before=tool('cad_open_project',{'path':str(project),'discardChanges':True})
            canonical=tool('cad_get_sketch',{'id':'multi'})
            assert len(canonical['sketch']['elements'])==2
            tool('cad_set_sketch',dict(id='multi',name='Replacement',plane='XY',shape='RECTANGLE',u='0 mm',v='0 mm',width='30 mm'),error=True)
            after=tool('cad_get_model')
            assert after['sketches']==before['sketches'] and after['features']==before['features'] and after['bodies']==before['bodies']
        opened=tool('cad_open_file', {'path': str(root / 'samples/bracket.obj'), 'discardChanges': True})
        unchanged=tool('cad_get_model')
        job=tool('cad_analyze_mesh')
        assert job['status']=='running' and job['unit']==unchanged['display']['unit'] and not job['modelChanged']
        analysis=None
        for _ in range(300):
            analysis=tool('cad_get_mesh_analysis',{'jobId':job['jobId'],'offset':0,'limit':1})
            if analysis['status']=='complete': break
            time.sleep(.01)
        assert analysis and analysis['status']=='complete' and analysis['revision']
        assert analysis['unit']==job['unit'] and analysis['quality']['triangles']==opened['model']['triangles']
        assert analysis['componentCount']>=1 and analysis['componentOffset']==0
        assert 'cylindricalPatches' in analysis and 'repeatedCylindricalPatterns' in analysis
        assert 'featureCandidates' in analysis and analysis['featureCandidateCount']>=1
        assert analysis['pagination']['limit']==1 and analysis['planarPatchOffset']==0
        assert tool('cad_get_model')['sceneBodies']==unchanged['sceneBodies']
        with tempfile.TemporaryDirectory(dir=root/'build/qa',prefix='mesh-copy-e2e-') as temporary:
            folder=Path(temporary);source=folder/'monitor-mount.stl'
            assert make_monitor_reference(source)>0
            fixtureOutput=os.environ.get('KCAD_TEST_FIXTURE_OUTPUT')
            if fixtureOutput:
                target=Path(fixtureOutput);target.mkdir(parents=True,exist_ok=True);shutil.copyfile(source,target/'monitor-mount.stl')
            tool('cad_set_display',{'unit':'mm'})
            loaded=tool('cad_open_file',{'path':str(source),'discardChanges':True})
            assert loaded['model']['dimensions']=={'x':120.0,'y':80.0,'z':6.0}
            meshJob=tool('cad_analyze_mesh');meshAnalysis=None
            for _ in range(500):
                meshAnalysis=tool('cad_get_mesh_analysis',{'jobId':meshJob['jobId'],'offset':0,'limit':100})
                if meshAnalysis['status']=='complete': break
                time.sleep(.01)
            assert meshAnalysis and meshAnalysis['status']=='complete'
            proposal=tool('cad_suggest_parametric_copy',{'analysisJobId':meshJob['jobId'],'openingDiameter':22.0})
            assert proposal['status']=='ready',proposal
            assert abs(proposal['opening']['originalDiameter']-20.0)<.7 and proposal['opening']['proposedDiameter']==22.0
            assert proposal['surfaceDeviation']['samplesPerDirection']>0
            assert proposal['surfaceDeviation']['sourceToCopy']['rms']>0.0
            preview=tool('cad_preview_parametric_copy',{'proposalId':proposal['proposalId']})
            assert preview['preview']['valid'] and preview['copyVolume']<preview['sourceVolume']
            assert preview['surfaceDeviation']['copyToSource']['maximum']>=preview['surfaceDeviation']['copyToSource']['rms']
            assert tool('cad_get_model')['features']==[]
            accepted=tool('cad_accept_parametric_copy',{'proposalId':proposal['proposalId']})
            assert len(accepted['features'])==1 and len(accepted['sketches'])==1
            assert len(tool('cad_undo')['features'])==0
            accepted=tool('cad_redo');assert len(accepted['features'])==1
            copySketch=tool('cad_get_sketch',{'id':accepted['sketches'][0]['id']})['sketch']
            assert len(copySketch['elements'])==7
            radius=next(p for p in accepted['parameters'] if p['name'].startswith('openingRadius_'))
            assert radius['value']==11.0
            changed=tool('cad_set_parameter',{'name':radius['name'],'expression':'12 mm'})
            assert next(p for p in changed['parameters'] if p['name']==radius['name'])['value']==12.0
            assert any(e['width']==radius['name'] for e in tool('cad_get_sketch',{'id':copySketch['id']})['sketch']['elements'])
            assert next(p for p in tool('cad_undo')['parameters'] if p['name']==radius['name'])['value']==11.0
            redone=tool('cad_redo');assert next(p for p in redone['parameters'] if p['name']==radius['name'])['value']==12.0
            project=folder/'copy.kcad';tool('cad_save_project',{'path':str(project)})
            restored=tool('cad_open_project',{'path':str(project),'discardChanges':True})
            assert len(restored['features'])==1 and len(restored['sketches'][0]['elements'])==7
            exported=tool('cad_export_mesh',{'path':str(folder/'copy.obj')})
            assert exported['parts']==2
            ambiguous=folder/'ambiguous-monitor.stl';make_monitor_reference(ambiguous,ambiguous=True)
            tool('cad_open_file',{'path':str(ambiguous),'discardChanges':True})
            tool('cad_set_display',{'unit':'cm'})
            missingUnitJob=tool('cad_analyze_mesh')
            for _ in range(500):
                missingUnitAnalysis=tool('cad_get_mesh_analysis',{'jobId':missingUnitJob['jobId'],'offset':0,'limit':100})
                if missingUnitAnalysis['status']=='complete': break
                time.sleep(.01)
            assert missingUnitAnalysis['unit']=='cm'
            tool('cad_suggest_parametric_copy',{'analysisJobId':missingUnitJob['jobId']},error=True)
            tool('cad_set_display',{'unit':'mm'})
            ambiguousJob=tool('cad_analyze_mesh')
            for _ in range(500):
                ambiguousAnalysis=tool('cad_get_mesh_analysis',{'jobId':ambiguousJob['jobId'],'offset':0,'limit':100})
                if ambiguousAnalysis['status']=='complete': break
                time.sleep(.01)
            choice=tool('cad_suggest_parametric_copy',{'analysisJobId':ambiguousJob['jobId'],'openingDiameter':22.0})
            assert choice['status']=='needs-selection' and len(choice['candidateOpenings'])==2
            selected=tool('cad_suggest_parametric_copy',{'analysisJobId':ambiguousJob['jobId'],'openingContourId':choice['candidateOpenings'][0]['contourId'],'openingDiameter':22.0})
            assert selected['status']=='ready' and selected['opening']['contourId']==choice['candidateOpenings'][0]['contourId']
            tool('cad_discard_parametric_copy',{'proposalId':selected['proposalId']})
            rack=folder/'fused-rack.stl';assert make_squat_rack_reference(rack)>0
            if fixtureOutput: shutil.copyfile(rack,Path(fixtureOutput)/'squat-rack.stl')
            tool('cad_open_file',{'path':str(rack),'discardChanges':True})
            rackJob=tool('cad_analyze_mesh');rackAnalysis=None
            for _ in range(600):
                rackAnalysis=tool('cad_get_mesh_analysis',{'jobId':rackJob['jobId'],'offset':0,'limit':100})
                if rackAnalysis['status']=='complete': break
                time.sleep(.01)
            assert rackAnalysis and rackAnalysis['status']=='complete' and rackAnalysis['revision']==rackJob.get('revision',rackAnalysis['revision'])
            rackProposal=tool('cad_suggest_parametric_copy',{'analysisJobId':rackJob['jobId']})
            assert rackProposal['modelKind']=='squat-rack' and rackProposal['assembly']['partCount']==5
            assert len(rackProposal['features'])==5 and not rackProposal['sketches']
            rackPreview=tool('cad_preview_parametric_copy',{'proposalId':rackProposal['proposalId']})
            assert rackPreview['preview']['valid'] and rackPreview['surfaceDeviation']['samplesPerDirection']>0
            rackAccepted=tool('cad_accept_parametric_copy',{'proposalId':rackProposal['proposalId']})
            assert len(rackAccepted['features'])==5 and len(rackAccepted['sceneBodies'])==6
            wall=next(p for p in rackAccepted['parameters'] if p['name'].startswith('rackWallThickness_'))
            assert abs(wall['value']-3.0)<.2
            changedWall=tool('cad_set_parameter',{'name':wall['name'],'expression':'4 mm'})
            assert abs(next(p for p in changedWall['parameters'] if p['name']==wall['name'])['value']-4.0)<.01
            assert len(changedWall['features'])==5
            assert any(b['id'].startswith('rack-left-post-') for b in changedWall['sceneBodies'])
            revertedWall=tool('cad_undo');assert abs(next(p for p in revertedWall['parameters'] if p['name']==wall['name'])['value']-3.0)<.01
            tool('cad_undo');assert not tool('cad_get_model')['features']
            tool('cad_redo');restoredRack=tool('cad_get_model');assert len(restoredRack['features'])==5
            assert abs(next(p for p in restoredRack['parameters'] if p['name']==wall['name'])['value']-3.0)<.01
            redoneRack=tool('cad_redo');assert abs(next(p for p in redoneRack['parameters'] if p['name']==wall['name'])['value']-4.0)<.01
            rackProject=folder/'rack-copy.kcad';tool('cad_save_project',{'path':str(rackProject)})
            loadedRack=tool('cad_open_project',{'path':str(rackProject),'discardChanges':True})
            assert len(loadedRack['features'])==5 and len(loadedRack['sceneBodies'])==6
            rackExport=tool('cad_export_mesh',{'path':str(folder/'rack-copy.stl')});assert rackExport['parts']==6
        p.stdin.close()
        p.wait(timeout=10)
        assert p.returncode == 0, log.read_text()
        print(f'MCP_E2E_OK tools={len(tools)} png={width}x{height} lifecycle=clean')
    finally:
        if p.poll() is None:
            p.terminate()
            p.wait(timeout=5)
