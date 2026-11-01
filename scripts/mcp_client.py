#!/usr/bin/env python3
"""Use the installed CAD app's MCP adapter; never launch a GUI or guess schemas."""
import argparse, json, selectors, subprocess, sys, time
from pathlib import Path

class CadClient:
    def __init__(self, app=None, socket=None):
        app=Path(app) if app else Path.home()/'Applications/Kotlin CAD.app/Contents/MacOS/Kotlin CAD'
        if not app.is_file(): raise RuntimeError('Installed Kotlin CAD executable not found')
        command=[str(app),'--mcp']
        if socket: command += ['--socket',str(socket)]
        self.process=subprocess.Popen(command,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,bufsize=0)
        self.selector=selectors.DefaultSelector();self.selector.register(self.process.stdout,selectors.EVENT_READ)
        self.sequence=0;self.buffer=b''
        try:
            self.request('initialize',{'protocolVersion':'2024-11-05','capabilities':{},'clientInfo':{'name':'cad-projects','version':'1'}})
            self.send({'jsonrpc':'2.0','method':'notifications/initialized'})
        except BaseException:
            self.close();raise
    def send(self,value):
        data=memoryview((json.dumps(value)+'\n').encode())
        while data:
            written=self.process.stdin.write(data)
            if not written:raise RuntimeError('CAD adapter input closed')
            data=data[written:]
    def request(self,method,params):
        self.sequence+=1;self.send({'jsonrpc':'2.0','id':self.sequence,'method':method,'params':params});deadline=time.monotonic()+70
        while True:
            while b'\n' not in self.buffer:
                remaining=deadline-time.monotonic()
                if remaining<=0 or not self.selector.select(remaining): raise TimeoutError('CAD request timed out; do not repeat a mutation without inspecting the model')
                chunk=self.process.stdout.read(65536)
                if not chunk: raise RuntimeError('CAD adapter closed; check that the installed app is running')
                self.buffer+=chunk
                if len(self.buffer)>40_000_000: raise RuntimeError('CAD response exceeds limit')
            line,self.buffer=self.buffer.split(b'\n',1);reply=json.loads(line)
            if reply.get('id')==self.sequence:
                if 'error' in reply: raise RuntimeError(str(reply['error']))
                return reply['result']
    def call(self,name,args):
        result=self.request('tools/call',{'name':name,'arguments':args})
        if result.get('isError'): raise RuntimeError(json.dumps(result,ensure_ascii=False))
        return result
    def close(self):
        self.selector.close()
        if self.process.poll() is None:
            self.process.terminate()
            try:self.process.wait(timeout=5)
            except subprocess.TimeoutExpired:self.process.kill();self.process.wait()
    def __enter__(self):return self
    def __exit__(self,*args):self.close()

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--app');parser.add_argument('--socket')
    sub=parser.add_subparsers(dest='command',required=True);sub.add_parser('tools')
    call=sub.add_parser('call');call.add_argument('name');call.add_argument('--arguments-file',type=Path);call.add_argument('--output',type=Path)
    args=parser.parse_args()
    with CadClient(args.app,args.socket) as client:
        if args.command=='tools':result=client.request('tools/list',{})
        else:result=client.call(args.name,json.loads(args.arguments_file.read_text()) if args.arguments_file else {})
    encoded=json.dumps(result,ensure_ascii=False,indent=2)+'\n'
    if getattr(args,'output',None):
        with args.output.open('x') as stream:stream.write(encoded)
        print(args.output)
    else:print(encoded)
if __name__=='__main__':
    try:main()
    except Exception as e:print(str(e),file=sys.stderr);sys.exit(1)
