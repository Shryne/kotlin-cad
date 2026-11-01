#!/usr/bin/env python3
"""Offline tests: no GUI, live documents, credentials or installed app required."""
import importlib.util,json,tempfile,unittest,zipfile,os
from pathlib import Path
from unittest.mock import patch
ROOT=Path(__file__).resolve().parents[1]
def load(name):
 spec=importlib.util.spec_from_file_location(name,ROOT/'skills/kotlin-cad-projects/scripts'/f'{name}.py')
 module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module
project=load('project');cad=load('cad')
class ModellingSkillTest(unittest.TestCase):
 def test_revisions_preserve_saved_content(self):
  with tempfile.TemporaryDirectory() as tmp:
   root=Path(tmp)/'model';project.initialise(root,'Demo','Synthetic test')
   first=(root/'revisions/r0001.kcad').read_bytes()
   self.assertEqual('unchanged',project.checkpoint(root,'No change','None')['status'])
   with zipfile.ZipFile(root/'current.kcad') as z:model=json.loads(z.read('project.json'))
   self.assertEqual(12,model['version'])
   model['bodyColors']={'body-demo':{'base':'#336699'}}
   model['features']=[{'id':'feature-demo','kind':'test-definition','expression':'length'}]
   with zipfile.ZipFile(root/'current.kcad','w') as z:z.writestr('project.json',json.dumps(model))
   result=project.checkpoint(root,'Synthetic metadata change','Helper integrity only; not geometry validation')
   self.assertEqual('r0002',result['revision'])
   record=json.loads((root/'revisions/r0002.json').read_text())
   self.assertEqual(model,record['model']);self.assertEqual('r0001',record['parentRevision'])
   self.assertEqual(first,(root/'revisions/r0001.kcad').read_bytes())
   self.assertEqual((root/'current.kcad').read_bytes(),(root/'revisions/r0002.kcad').read_bytes())
   with self.assertRaises(ValueError):project.initialise(root,'Again','')
   (root/'revisions/r0001.kcad').write_bytes(b'corrupted')
   with self.assertRaises(ValueError):project.checkpoint(root,'Attempt','None')
 def test_unknown_format_does_not_create_revision(self):
  with tempfile.TemporaryDirectory() as tmp:
   root=Path(tmp)/'model';project.initialise(root,'Demo','')
   with zipfile.ZipFile(root/'current.kcad','w') as z:z.writestr('project.json',json.dumps({'format':'kotlin-cad','version':99}))
   before=(root/'.cad-project.json').read_bytes()
   with self.assertRaises(ValueError):project.checkpoint(root,'Attempt','None')
   self.assertEqual(before,(root/'.cad-project.json').read_bytes())
   self.assertFalse((root/'revisions/r0002.kcad').exists())
 def test_app_location_is_configurable(self):
  with tempfile.TemporaryDirectory() as tmp:
   bundle=Path(tmp)/'Demo.app';binary=bundle/'Contents/MacOS/Kotlin CAD';binary.parent.mkdir(parents=True);binary.touch()
   with patch.dict(os.environ,{'KOTLIN_CAD_APP':str(bundle)}),patch.object(cad.subprocess,'Popen',side_effect=RuntimeError('intercepted')) as launch:
    with self.assertRaisesRegex(RuntimeError,'intercepted'):cad.CadClient()
    self.assertEqual([str(binary),'--mcp'],launch.call_args.args[0])
if __name__=='__main__':unittest.main()
