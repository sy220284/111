#!/usr/bin/env python3
"""Real inherited Unix socket descriptor / JSONL protocol acceptance of appifact_sdk.js.
Host is isolated and backed by temporary real files. No fake global claude object,
cloud credentials or production Artifact service. A production host still needs testing.
"""
import hashlib
import json
import os
import pathlib
import socket
import subprocess
import tempfile
import threading
import unittest

ROOT=pathlib.Path(__file__).resolve().parents[1]
SDK=ROOT/'tools/bundled/artifact/scripts/appifact_sdk.js'
# The embedded .js is a CommonJS SDK inside a type:module repository; load it
# into Node's actual CJS module wrapper rather than misexecuting it as ESM.
CJS_BOOT="const fs=require('node:fs'),M=require('node:module'),p=require('node:path');const f=process.argv[1],m=new M(f);m.filename=f;m.paths=M._nodeModulePaths(p.dirname(f));m._compile(fs.readFileSync(f,'utf8'),f)"

class Host:
    def __init__(self, directory):
        self.dir=pathlib.Path(directory)
        self.docs={}
        self.assets={}
        self.files={}
        self.requests=[]
        self.errors=[]
        self.saved=self.dir/'served-document.txt'
        self.asset_file=self.dir/'downloaded-asset.txt'
    def handle(self, msg):
        verb=msg.get('verb')
        args=msg.get('args',{})
        self.requests.append((verb,args))
        if verb in ('set','update','delete','get'):
            key=args['collection']+'/'+args['doc_id']
            old=self.docs.get(key)
            if verb=='get':
                return {'db_read':{'docs':[] if old is None else
                    [{'id':args['doc_id'],'version':old[0],'data':old[1]}]}}
            if verb=='update' and old and args.get('if_version')!=old[0]:
                return None,{'code':'conflict','reason':'version_mismatch','message':'stale update'}
            if verb=='delete':
                self.docs.pop(key,None)
                return {'db_write':{'version':(old[0]+1) if old else 1}}
            self.docs[key]=((old[0]+1) if old else 1,args['data'])
            return {'db_write':{'version':self.docs[key][0]}}
        if verb=='publish':
            source=pathlib.Path(args['file_path'])
            root=pathlib.Path(args['root'])
            if not source.is_relative_to(root) or not source.is_file():
                return None,{'code':'invalid_path','message':'outside publish root'}
            self.files['document.txt']=source.read_bytes()
            return {'file_write':{'published':True}}
        if verb=='list_files':
            return {'file_list':{'files':[{'path':k} for k in self.files]}}
        if verb=='read_file':
            data=self.files[args['path']]
            self.saved.write_bytes(data)
            return {'file_read':{'saved_to':str(self.saved),'sha256':hashlib.sha256(data).hexdigest(),
                'as_served':False,'working_copy':False}}
        if verb=='upload_asset':
            source=pathlib.Path(args['file_path'])
            if not source.name=='document.txt' or not source.is_file():
                return None,{'code':'invalid_path','message':'upload file missing'}
            self.assets['asset-test']=source.read_bytes()
            return {'asset_upload':{'id':'asset-test','url':'asset://test','content_type':'text/plain',
                'size_bytes':len(self.assets['asset-test'])}}
        if verb=='read_asset':
            data=self.assets[args['asset_id']]
            self.asset_file.write_bytes(data)
            return {'asset_read':{'id':args['asset_id'],'path':str(self.asset_file),
                'sha256':hashlib.sha256(data).hexdigest(),
                'content_type':'text/plain','size_bytes':len(data)}}
        raise ValueError('unexpected SDK verb '+str(verb))
    def serve(self,sock):
        try:
            with sock.makefile('r',encoding='utf8') as stream:
                while True:
                    line=stream.readline()
                    if not line:break
                    msg=json.loads(line)
                    if msg.get('type')=='see':
                        response={'type':'artifact_result','id':msg['id'],'ok':True,'result':{}}
                    elif msg.get('type')=='artifact_call':
                        result=self.handle(msg)
                        if isinstance(result,tuple):
                            response={'type':'artifact_result','id':msg['id'],'ok':False,'error':result[1]}
                        else:
                            response={'type':'artifact_result','id':msg['id'],'ok':True,'result':result}
                    else:
                        raise ValueError('unknown wire message '+str(msg.get('type')))
                    sock.sendall((json.dumps(response)+'\n').encode())
        except Exception as error:
            self.errors.append(str(error))
        finally:sock.close()

class SDKProtocolTest(unittest.TestCase):
    def test_real_sdk_subprocess_unix_fd_rpc_and_resource_cleanup(self):
        with tempfile.TemporaryDirectory(prefix='111-v37-artifact-fd-') as directory:
            host=Host(directory)
            server,client=socket.socketpair()
            env={**os.environ,'CLAUDE_CODE_ARTIFACT_FD':str(client.fileno()),
                 'APPIFACT_ARTIFACT_URL':'artifact://isolated-host','NODE_NO_WARNINGS':'1'}
            script="""
const fs=require('node:fs');
const db=await claude.use('db');
const doc=db.doc('records/alpha');
await doc.set({value:1});
const first=await doc.get();
if(first.data().value!==1 || !Object.isFrozen(first.data()))throw Error('document snapshot invalid');
await doc.update({value:2});
const second=await doc.get();
if(second.data().value!==2)throw Error('versioned update failed');
const files=await claude.use('files');
const dir=files.dir();
fs.writeFileSync(require('node:path').join(dir,'document.txt'),'real-fd-document');
await files.publish({file_path:'document.txt'});
const listing=await files.list();
if(!listing.some(x=>x.path==='document.txt'))throw Error('file listing wrong');
const read=await files.read('document.txt');
if(read!=='real-fd-document')throw Error('digest-backed file bytes differ');
const assets=await claude.use('assets');
const uploaded=await assets.upload('document.txt');
const downloaded=await assets.download(uploaded.id);
if(fs.readFileSync(downloaded.path,'utf8')!=='real-fd-document')throw Error('download content wrong');
const shown=await claude.see({text:'test marker'});
if(!shown.ok)throw Error('real see frame was refused');
console.log('SDK_FD_HOST_OK');
"""
            proc=subprocess.Popen(['node','-e',CJS_BOOT,str(SDK)],cwd=directory,env=env,
                stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,
                pass_fds=(client.fileno(),))
            client.close()
            thread=threading.Thread(target=host.serve,args=(server,),daemon=True)
            thread.start()
            try:stdout,stderr=proc.communicate(script.encode(),timeout=30)
            except subprocess.TimeoutExpired:
                proc.kill();stdout,stderr=proc.communicate()
                self.fail('SDK hung on descriptor RPC: '+stderr.decode(errors='replace')[-1300:])
            thread.join(5)
            self.assertEqual(proc.returncode,0,stderr.decode(errors='replace')[-1800:])
            self.assertFalse(host.errors,host.errors)
            self.assertIn(b'SDK_FD_HOST_OK',stdout)
            verbs=[x[0] for x in host.requests]
            self.assertEqual(verbs[:4],['set','get','update','get'])
            self.assertTrue({'publish','list_files','read_file','upload_asset','read_asset'}<=set(verbs))
            self.assertEqual(host.docs['records/alpha'],(2,{'value':2}))
            self.assertFalse(list(pathlib.Path(directory).glob('.appifact-publish-*')),
                'SDK failed to destroy private publication directory on exit')
    def test_missing_fd_is_refused_instead_of_inventing_host(self):
        with tempfile.TemporaryDirectory() as d:
            env={k:v for k,v in os.environ.items() if k!='CLAUDE_CODE_ARTIFACT_FD'}
            env['APPIFACT_ARTIFACT_URL']='artifact://isolated-host'
            r=subprocess.run(['node','-e',CJS_BOOT,str(SDK)],cwd=d,env=env,timeout=15,
                input=b"await (await claude.use('db')).doc('records/a').get()",capture_output=True)
            self.assertNotEqual(r.returncode,0)
            self.assertIn(b'no pipe was given',r.stdout+r.stderr)
if __name__=='__main__':unittest.main(verbosity=2)
