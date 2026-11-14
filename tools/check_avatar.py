#!/usr/bin/env python3
"""Validate the bundled GLB and JavaScript without Android SDK. Run: python3 tools/check_avatar.py"""
from pathlib import Path
import json,struct,subprocess,sys
root=Path(__file__).resolve().parents[1]/'app/src/main/assets/layaba'
p=root/'layaba_realistic.glb'; b=p.read_bytes()
assert len(b)>=20 and b[:4]==b'glTF' and struct.unpack_from('<II',b,4)==(2,len(b)), 'Invalid GLB header'
off=12; data=None
while off<len(b):
    assert off+8<=len(b),'Truncated chunk header'
    size,kind=struct.unpack_from('<II',b,off);off+=8
    assert off+size<=len(b),'Truncated chunk'
    if kind==0x4E4F534A:data=json.loads(b[off:off+size].decode('utf8'))
    off+=size
assert data and data.get('asset',{}).get('version')=='2.0','Missing glTF JSON'
assert data.get('skins') and data.get('animations'),'Expected rig and animations'
for skin in data['skins']:
    assert all(0<=j<len(data['nodes']) for j in skin['joints']),'Invalid joint reference'
for m in data.get('meshes',[]):
    for primitive in m.get('primitives',[]):
        attrs=primitive.get('attributes',{})
        assert 'POSITION' in attrs,'Mesh missing positions'
        if 'JOINTS_0' in attrs:assert 'WEIGHTS_0' in attrs,'Skinned mesh missing weights'
for name in ('layaba.js','OfflineGLBLoader.js','OrbitControls.js'):
    f=root/name
    r=subprocess.run(['node','--check',str(f)],capture_output=True,text=True)
    assert r.returncode==0, name+': '+r.stderr
print('PASS: GLB glTF 2.0; meshes',len(data.get('meshes',[])),'skins',len(data['skins']),'animations',len(data['animations']),'JS syntax OK')
print('NOTE: This is not a visual, Android build, or photorealism test.')
