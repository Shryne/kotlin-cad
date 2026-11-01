#!/usr/bin/env python3
"""Regression tests for the publication guard; all secret-like values are fabricated."""
import subprocess,sys,tempfile,unittest,hashlib,json
from pathlib import Path
GUARD=Path(__file__).with_name('check_secrets.py').resolve()
class GuardTest(unittest.TestCase):
 def scan(self,root):return subprocess.run([sys.executable,str(GUARD),str(root)],capture_output=True,text=True)
 def reject(self,value,name='example.txt'):
  with tempfile.TemporaryDirectory() as folder:
   root=Path(folder);path=root/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(value)
   result=self.scan(root);self.assertNotEqual(0,result.returncode);self.assertNotIn(value,result.stdout+result.stderr)
 def test_token_families(self):
  for prefix,size in [('sk-',48),('ghp_',36),('github_pat_',60),('xoxb-',30),('AIza',35)]:
   with self.subTest(prefix=prefix):self.reject(prefix+'z'*size)
 def test_personal_path(self):self.reject('/Users/'+'private-owner/'+'Documents/design')
 def test_private_config(self):self.reject('sensitive-placeholder','auth.json')
 def test_private_project(self):self.reject('private-placeholder','design.kcad')
 def test_ignored_tracked_asset(self):
  with tempfile.TemporaryDirectory() as folder:
   root=Path(folder);subprocess.run(['git','init','--quiet',str(root)],check=True)
   (root/'samples').mkdir();(root/'samples/private.kcad').write_text('private-placeholder')
   subprocess.run(['git','-C',str(root),'add','samples/private.kcad'],check=True)
   (root/'.gitignore').write_text('samples/\n')
   self.assertNotEqual(0,self.scan(root).returncode)
 def test_reviewed_images(self):
  with tempfile.TemporaryDirectory() as folder:
   root=Path(folder);images=root/'docs/images';images.mkdir(parents=True)
   data=b'\x89PNG\r\n\x1a\nsynthetic-test'
   image=images/'demo.png';image.write_bytes(data)
   self.assertNotEqual(0,self.scan(root).returncode)
   (images/'approved-assets.json').write_text(json.dumps({'demo.png':{'sha256':hashlib.sha256(data).hexdigest()}}))
   self.assertEqual(0,self.scan(root).returncode)
   image.write_bytes(data+b'changed')
   self.assertNotEqual(0,self.scan(root).returncode)
   image.write_bytes(data);(root/'demo.png').write_bytes(data)
   self.assertNotEqual(0,self.scan(root).returncode)
 def test_clean_source(self):
  with tempfile.TemporaryDirectory() as folder:
   root=Path(folder);(root/'main.kt').write_text('fun main() {}\n');self.assertEqual(0,self.scan(root).returncode)
if __name__=='__main__':unittest.main()
