#!/usr/bin/env python3
"""Local protocol fixture. Never connects to a model or opens a browser."""
import json,os,sys,time
from pathlib import Path
home=Path(os.environ['CODEX_HOME']); marker=home/'test-signed-in'
signed=marker.exists(); active=None; pending=None; stage=0; active_tool=None; analysis_job=None; proposal_id=None
assert 'OPENAI_API_KEY' not in os.environ
assert 'cli_auth_credentials_store="keyring"' in sys.argv

def write(value):print(json.dumps(value),flush=True)
def reply(i,value):write(dict(id=i,result=value))
def event(method,params):write(dict(method=method,params=params))
def done(text,status='completed'):
    event('item/agentMessage/delta',dict(threadId='qa-thread',turnId='qa-turn',itemId='answer',delta=text))
    event('item/completed',dict(threadId='qa-thread',turnId='qa-turn',item=dict(type='agentMessage',id='answer',text=text)))
    event('turn/completed',dict(threadId='qa-thread',turn=dict(id='qa-turn',status=status,error=None)))
def tool(name,args):
    global active_tool
    active_tool=name
    with (home/"qa-tools.jsonl").open("a") as log:log.write(json.dumps({"tool":name})+"\n")
    write(dict(id='tool-qa',method='item/tool/call',params=dict(threadId='qa-thread',turnId='qa-turn',callId='qa-call',tool=name,arguments=args)))
for line in sys.stdin:
    message=json.loads(line);method=message.get('method');i=message.get('id');p=message.get('params',{})
    if method is None and i=='tool-qa':
        success=message['result']['success'];content=message['result']['contentItems'][0]['text']
        if pending and pending.startswith('mesh-'):
            assert success,content
            value=json.loads(content)
            if active_tool=='cad_get_model':
                assert value['model'] and any(body['id']=='import' for body in value['sceneBodies'])
                stage=1;tool('cad_analyze_mesh',{})
            elif active_tool=='cad_analyze_mesh':
                assert value['status']=='running';analysis_job=value['jobId'];stage=2
                tool('cad_get_mesh_analysis',dict(jobId=analysis_job,offset=0,limit=100))
            elif active_tool=='cad_get_mesh_analysis':
                if value['status']!='complete':
                    tool('cad_get_mesh_analysis',dict(jobId=analysis_job,offset=0,limit=100))
                else:
                    assert value['revision'] and value['unit']=='mm'
                    stage=3;args=dict(analysisJobId=analysis_job)
                    if pending=='mesh-monitor':args['openingDiameter']=22.0
                    tool('cad_suggest_parametric_copy',args)
            elif active_tool=='cad_suggest_parametric_copy':
                if value['status']=='needs-selection':
                    candidates=value['candidateOpenings'];assert candidates
                    chosen=max(candidates,key=lambda candidate:candidate['diameter'])
                    tool('cad_suggest_parametric_copy',dict(analysisJobId=analysis_job,openingContourId=chosen['contourId'],openingDiameter=22.0))
                else:
                    expected='plate' if pending=='mesh-monitor' else 'squat-rack'
                    count=1 if expected=='plate' else 5
                    assert value['status']=='ready' and value['modelKind']==expected and len(value['features'])==count
                    if expected=='plate':assert abs(value['opening']['proposedDiameter']-22.0)<.01
                    proposal_id=value['proposalId'];stage=4;tool('cad_preview_parametric_copy',dict(proposalId=proposal_id))
            elif active_tool=='cad_preview_parametric_copy':
                assert value['preview']['valid'] and value['surfaceDeviation']['samplesPerDirection']>0
                stage=5;tool('cad_accept_parametric_copy',dict(proposalId=proposal_id))
            elif active_tool=='cad_accept_parametric_copy':
                expected=1 if pending=='mesh-monitor' else 5
                assert len(value['features'])==expected and len(value['sceneBodies'])==expected+1
                assert any(body['id']=='import' for body in value['sceneBodies'])
                done(f'Parametrische Kopie bestätigt: {expected} Körper; das Original bleibt erhalten.')
            else:raise AssertionError(f'Unexpected mesh tool at stage {stage}: {active_tool}')
        elif pending=='normal' and stage==0:
            assert success
            context=json.loads(content);assert 'selection' in context
            stage=1;tool('cad_set_parameter',dict(name='chat_breite',expression='42 mm'))
        else:
            assert success==(pending!='forbidden')
            done('Breite auf 42 mm gesetzt.' if pending=='normal' else 'Nicht ausgeführt.')
        continue
    if method=='initialize':reply(i,dict(userAgent='CAD test',platformFamily='unix',platformOs='macos'))
    elif method=='account/read':reply(i,dict(account=dict(type='chatgpt',email='test@example.invalid',planType='plus') if signed else None,requiresOpenaiAuth=True))
    elif method=='account/login/start':
        reply(i,dict(type='chatgpt',loginId='qa-login',authUrl='https://auth.openai.com/authorize'))
        marker.write_text('protocol fixture');signed=True
        event('account/login/completed',dict(loginId='qa-login',success=True,error=None))
    elif method=='account/logout':
        marker.unlink(missing_ok=True);signed=False;reply(i,{})
        event('account/updated',dict(authMode=None,planType=None))
    elif method=='account/login/cancel':reply(i,{})
    elif method=='model/list':reply(i,dict(data=[dict(id='qa-model',model='qa-model',displayName='Testmodell',isDefault=True)],nextCursor=None))
    elif method in ('thread/start','thread/resume'):
        assert p['environments']==[] and p['permissions']=='cad_chat' and 'sandbox' not in p and p['approvalPolicy']=='never'
        assert not {'cad_open_file','cad_save_project','cad_open_project','cad_export_mesh'}.intersection(t['name'] for t in p['dynamicTools'])
        if method=='thread/resume' and (home/'test-missing-rollout').exists():
            write(dict(id=i,error=dict(code=-32600,message='no rollout found for thread qa-thread')));continue
        reply(i,dict(thread=dict(id='qa-thread')))
    elif method=='turn/start':
        assert p['permissions']=='cad_chat' and p['environments']==[] and 'sandboxPolicy' not in p
        text=p['input'][0]['text'].rsplit('Auftrag:\n',1)[-1];active=i
        reply(i,dict(turn=dict(id='qa-turn',status='inProgress')))
        event('turn/started',dict(threadId='qa-thread',turn=dict(id='qa-turn',status='inProgress')))
        mode=(home/'test-response-mode').read_text().strip() if (home/'test-response-mode').exists() else ''
        if mode=='delayed':time.sleep(.25)
        if mode in ('immediate','delayed'):
            done('Testantwort.');continue
        if mode=='error':
            event('turn/completed',dict(threadId='qa-thread',turn=dict(id='qa-turn',status='failed',error=dict(message='Testfehler'))));continue
        if 'warte' in text.lower():pending='wait'
        elif 'verboten' in text.lower():pending='forbidden';tool('cad_open_file',dict(path='/not-allowed'))
        elif 'monitor' in text.lower():pending='mesh-monitor';stage=0;tool('cad_get_model',{})
        elif 'rack' in text.lower():pending='mesh-rack';stage=0;tool('cad_get_model',{})
        else:pending='normal';stage=0;tool('cad_get_model',{})
    elif method=='turn/interrupt':reply(i,{});done('Angehalten.','interrupted')
    elif method is not None and i is not None:reply(i,{})
