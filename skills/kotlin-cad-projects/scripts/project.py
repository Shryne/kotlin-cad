#!/usr/bin/env python3
"""Initialise a modelling folder or checkpoint its SAVED current.kcad; no live-app changes."""
import argparse, datetime, fcntl, hashlib, io, json, os, re, tempfile, uuid, zipfile
from pathlib import Path

SKILL=Path(__file__).resolve().parents[1]
def now():return datetime.datetime.now(datetime.timezone.utc).isoformat(timespec='seconds')
def atomic(path,data):
    fd,name=tempfile.mkstemp(prefix='.'+path.name+'-',dir=path.parent)
    try:
        with os.fdopen(fd,'wb') as out:out.write(data);out.flush();os.fsync(out.fileno())
        os.replace(name,path)
    finally:
        if os.path.exists(name):os.unlink(name)
def encode(value):return (json.dumps(value,ensure_ascii=False,indent=2)+'\n').encode()
def digest(data):return hashlib.sha256(data).hexdigest()
def inspect(data):
    if len(data)>160*1024*1024:raise ValueError('CAD file exceeds size limit')
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        names=z.namelist()
        if len(names)!=len(set(names)) or set(names)-{'project.json','mesh.bin'} or 'project.json' not in names:raise ValueError('Unexpected CAD archive contents')
        if z.getinfo('project.json').file_size>1048576:raise ValueError('CAD metadata too large')
        project=json.loads(z.read('project.json'))
        if project.get('format')!='kotlin-cad' or project.get('version') not in (10,11,12):raise ValueError('Checkpoint helper supports project schemas 10–12; inspect a newer format before updating this helper')
        canonical=json.dumps(project,sort_keys=True,separators=(',',':')).encode()
        h=hashlib.sha256(canonical)
        if 'mesh.bin' in names:
            if z.getinfo('mesh.bin').file_size>90_000_000:raise ValueError('CAD mesh too large')
            h.update(z.read('mesh.bin'))
        return project,h.hexdigest()
def checkpoint(folder,reason,validation):
    folder=Path(folder).resolve()
    manifest_path=folder/'.cad-project.json'
    if not manifest_path.is_file():raise ValueError('Not an initialised CAD modelling folder')
    if any((folder/p).is_symlink() for p in ['current.kcad','revisions','.cad-project.json','CHANGELOG.md','.cad-project.lock']):raise ValueError('Project paths must not be symbolic links')
    with (folder/'.cad-project.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX)
        manifest=json.loads(manifest_path.read_text());source=folder/'current.kcad'
        data=source.read_bytes();model,content_hash=inspect(data)
        records=[]
        for p in sorted((folder/'revisions').glob('r*.json')):
            if not re.fullmatch(r'r[0-9]{4,}\.json',p.name):continue
            r=json.loads(p.read_text());snap=folder/'revisions'/(p.stem+'.kcad')
            if not snap.is_file() or digest(snap.read_bytes())!=r['fileSha256']:raise ValueError('Revision integrity check failed: '+p.stem)
            records.append(r)
        if records and manifest.get('latestRevision')!=records[-1]['revision']:raise ValueError('Revision log and manifest disagree; inspect interrupted checkpoint before proceeding')
        if records and records[-1]['contentSha256']==content_hash:return {'status':'unchanged','revision':records[-1]['revision']}
        number=int(records[-1]['revision'][1:])+1 if records else 1
        revision=f'r{number:04d}';parent=records[-1]['revision'] if records else None
        record={'revision':revision,'parentRevision':parent,'createdAt':now(),'reason':reason,'validation':validation,
                'fileSha256':digest(data),'contentSha256':content_hash,'model':model}
        destination=folder/'revisions'/(revision+'.kcad');record_path=destination.with_suffix('.json')
        if destination.exists() or record_path.exists():raise ValueError('Unrecorded revision files found; preserve and inspect them before continuing')
        # Exclusive creation protects old revisions even if a prior attempt was interrupted.
        with destination.open('xb') as stream:stream.write(data);stream.flush();os.fsync(stream.fileno())
        with record_path.open('xb') as stream:stream.write(encode(record));stream.flush();os.fsync(stream.fileno())
        manifest.update(latestRevision=revision,latestContentSha256=content_hash,updatedAt=record['createdAt'])
        atomic(manifest_path,encode(manifest))
        with (folder/'CHANGELOG.md').open('a') as stream:
            stream.write(f"\n## {revision} · {record['createdAt']}\n\n{reason}\n\nValidation: {validation}\n\nParent: {parent or 'none'} · [Model](revisions/{revision}.kcad) · [Record](revisions/{revision}.json)\n")
        return {'status':'saved','revision':revision,'path':str(destination),'sha256':record['fileSha256']}
def initialise(folder,name,brief):
    folder=Path(folder).resolve()
    if folder.exists() and any(folder.iterdir()):raise ValueError('Target is not empty; existing files were not modified')
    folder.mkdir(parents=True,exist_ok=True)
    for part in ['revisions','references','previews','exports']:(folder/part).mkdir()
    (folder/'AGENTS.md').write_text((SKILL/'assets/AGENTS.md').read_text())
    (folder/'PROJECT.md').write_text(f'''# {name}

## Goal

{brief or 'Not specified yet. Read the user request before choosing geometry.'}

## Requirements and acceptance

Record requested dimensions, tolerances, intended use and checks here before modelling.

## Coordinates and units

Default: millimetres, right-handed XYZ, Z up. Preserve reference/import conventions unless explicitly changed.

## Design decisions

No design decisions recorded yet. Distinguish user requirements from assumptions.

## References

Keep originals in references/ and record their source, scale and relevant dimensions here.
''')
    (folder/'STATUS.md').write_text('''# Current status

Initial empty model; see .cad-project.json for the latest checkpoint.

## Next action

Read PROJECT.md, inspect the live CAD session and preserve unrelated work. Open current.kcad, establish the requested dimensions, and build the first reviewable model.

## Open questions / blockers

No blockers assessed yet.

## Affected features

None; no geometry has been created.
''')
    (folder/'CHANGELOG.md').write_text('# Revision history\n\nSnapshots are immutable. Restoring an old design creates a new revision.\n')
    manifest={'schemaVersion':1,'projectId':str(uuid.uuid4()),'name':name,'workingModel':'current.kcad','latestRevision':None,'createdAt':now()}
    (folder/'.cad-project.json').write_bytes(encode(manifest))
    model={'format':'kotlin-cad','version':12,'bodyColors':{},'sketches':[],'features':[],'source':None,'mesh':None,'parameters':[],
           'display':{'wireframe':False,'grid':True,'visible':True,'unit':'mm','hiddenBodyIds':[]},
           'camera':{'yaw':-35.0,'pitch':25.0,'panX':0.0,'panY':0.0,'zoom':1.0}}
    with zipfile.ZipFile(folder/'current.kcad','x',zipfile.ZIP_DEFLATED) as z:z.writestr('project.json',encode(model))
    return checkpoint(folder,'Initial empty project.','Empty schema-12 project; no geometry to validate.')
def main():
    parser=argparse.ArgumentParser(description=__doc__);sub=parser.add_subparsers(dest='command',required=True)
    init=sub.add_parser('init');init.add_argument('folder',type=Path);init.add_argument('--name',required=True);init.add_argument('--brief',default='')
    save=sub.add_parser('checkpoint');save.add_argument('folder',type=Path);save.add_argument('--reason',required=True);save.add_argument('--validation',required=True)
    args=parser.parse_args()
    result=initialise(args.folder,args.name,args.brief) if args.command=='init' else checkpoint(args.folder,args.reason,args.validation)
    print(json.dumps(result,ensure_ascii=False))
if __name__=='__main__':main()
