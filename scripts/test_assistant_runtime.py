import json,subprocess,selectors,tempfile,os
from pathlib import Path
folder=Path(tempfile.mkdtemp(prefix='cad-auth-probe-',dir='/private/tmp'));(folder/'workspace').mkdir();(folder/'codex').mkdir()
import shutil,sys
cli=sys.argv[1] if len(sys.argv)>1 else shutil.which('codex')
assert cli,'Codex executable missing'
env={k:v for k,v in os.environ.items() if k in ['HOME','PATH','TMPDIR','USER','LOGNAME','LANG','SHELL']};env['CODEX_HOME']=str(folder/'codex')
args=[cli,'app-server','--listen','stdio://']
for setting in ['cli_auth_credentials_store="keyring"','forced_login_method="chatgpt"','features.shell_tool=false','features.apps=false','features.plugins=false','features.multi_agent=false','features.hooks=false','features.memories=false','features.shell_snapshot=false','web_search="disabled"','skills.bundled.enabled=false','skills.include_instructions=false','permissions.cad_chat.filesystem={":minimal"="read",":root"="deny"}','permissions.cad_chat.network.enabled=false','default_permissions="cad_chat"']:args+=['-c',setting]
p=subprocess.Popen(args,env=env,cwd=folder/'workspace',stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,text=True,bufsize=1)
s=selectors.DefaultSelector();s.register(p.stdout,selectors.EVENT_READ);seq=0
# Use a reader queue: text buffering may retain a second line beyond the selector.
import threading,queue
q=queue.Queue()
threading.Thread(target=lambda:[q.put(line) for line in p.stdout],daemon=True).start()
def req(method,params):
 global seq
 seq+=1;p.stdin.write(json.dumps({'id':seq,'method':method,'params':params})+'\n');p.stdin.flush()
 while True:
  r=json.loads(q.get(timeout=45))
  if r.get('id')==seq:
   if 'error' in r:raise RuntimeError((method,r['error']))
   print(method,'OK');return r['result']
try:
 req('initialize',{'clientInfo':{'name':'kotlin_cad_probe','version':'1'},'capabilities':{'experimentalApi':True}})
 p.stdin.write('{"method":"initialized"}\n');p.stdin.flush()
 account=req('account/read',{'refreshToken':False})
 assert account['account'] is None,'Fresh CAD account namespace unexpectedly shares a login'
 print('NO_SHARED_LOGIN; KEYRING_ONLY',not (folder/'codex'/'auth.json').exists())
 print(req('permissionProfile/list',{'cwd':str(folder/'workspace')}))
 thread=req('thread/start',{'cwd':str(folder/'workspace'),'permissions':'cad_chat','approvalPolicy':'never','environments':[],'dynamicTools':[{'type':'function','name':'cad_get_model','description':'Read CAD model','inputSchema':{'type':'object','properties':{}}}]})
 print('THREAD_PROTOCOL_OK',bool(thread['thread']['id']))
 req('turn/start',{'threadId':thread['thread']['id'],'permissions':'cad_chat','environments':[],'input':[{'type':'text','text':'Hallo'}]})
finally:p.stdin.close();p.terminate();p.wait(timeout=10)
