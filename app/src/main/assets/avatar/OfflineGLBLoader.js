/* Offline glTF 2.0 loader for bundled Three.js. Supports GLB meshes, morphs, joints, skins, and linear animation tracks. */
(function(){
function decode(buffer){
 if (!buffer || buffer.byteLength < 20 || buffer.byteLength > 32*1024*1024) throw Error('Invalid GLB size');
 const d=new DataView(buffer);if(d.getUint32(0,true)!==0x46546c67 || d.getUint32(4,true)!==2 || d.getUint32(8,true)!==buffer.byteLength)throw Error('Invalid GLB header');
 let at=12,g=null,bin=null;
 while(at<buffer.byteLength){if(at+8>buffer.byteLength)throw Error('Truncated GLB chunk');const len=d.getUint32(at,true),type=d.getUint32(at+4,true);at+=8;if(at+len>buffer.byteLength)throw Error('Invalid GLB chunk length');if(type===0x4e4f534a)g=JSON.parse(new TextDecoder().decode(new Uint8Array(buffer,at,len)));if(type===0x004e4942)bin=buffer.slice(at,at+len);at+=len;}
 if(!g||!bin)throw Error('Invalid GLB');
 const sizes={SCALAR:1,VEC2:2,VEC3:3,VEC4:4,MAT4:16},types={5120:Int8Array,5121:Uint8Array,5122:Int16Array,5123:Uint16Array,5125:Uint32Array,5126:Float32Array};
 function accessor(id){const a=g.accessors[id],v=g.bufferViews[a.bufferView],C=types[a.componentType],size=sizes[a.type];if(!C||!size||a.sparse)throw Error('Unsupported accessor');const n=a.count*size,off=(v.byteOffset||0)+(a.byteOffset||0);if(v.byteStride&&v.byteStride!==size*C.BYTES_PER_ELEMENT){const out=new C(n),dv=new DataView(bin);for(let i=0;i<a.count;i++)for(let k=0;k<size;k++){const p=off+i*v.byteStride+k*C.BYTES_PER_ELEMENT;out[i*size+k]=C===Float32Array?dv.getFloat32(p,true):C===Uint16Array?dv.getUint16(p,true):C===Uint32Array?dv.getUint32(p,true):C===Int16Array?dv.getInt16(p,true):C===Int8Array?dv.getInt8(p):dv.getUint8(p);}return out;}return new C(bin.slice(off,off+n*C.BYTES_PER_ELEMENT));}
 const mats=(g.materials||[]).map(m=>{const p=m.pbrMetallicRoughness||{},c=p.baseColorFactor||[1,1,1,1];return new THREE.MeshStandardMaterial({name:m.name||'',color:new THREE.Color(c[0],c[1],c[2]),opacity:c[3],transparent:c[3]<1,metalness:p.metallicFactor||0,roughness:p.roughnessFactor===undefined?1:p.roughnessFactor,side:m.doubleSided?THREE.DoubleSide:THREE.FrontSide,skinning:true,morphTargets:true});});
 const meshes=(g.meshes||[]).map(m=>(m.primitives||[]).filter(p=>p.mode===undefined||p.mode===4).map(p=>{const geo=new THREE.BufferGeometry();const names={POSITION:'position',NORMAL:'normal',TEXCOORD_0:'uv',COLOR_0:'color',JOINTS_0:'skinIndex',WEIGHTS_0:'skinWeight'};for(const [k,id] of Object.entries(p.attributes||{})){if(!names[k])continue;const a=g.accessors[id];geo.setAttribute(names[k],new THREE.BufferAttribute(accessor(id),sizes[a.type],!!a.normalized));}if(p.indices!==undefined)geo.setIndex(new THREE.BufferAttribute(accessor(p.indices),1));if(!geo.attributes.normal)geo.computeVertexNormals();if(p.targets){geo.morphAttributes.position=p.targets.map(t=>new THREE.Float32BufferAttribute(accessor(t.POSITION),3));geo.morphTargetsRelative=true;}const skin=!!(geo.attributes.skinIndex&&geo.attributes.skinWeight);const obj=skin?new THREE.SkinnedMesh(geo,mats[p.material]||new THREE.MeshStandardMaterial({skinning:true,morphTargets:true})):new THREE.Mesh(geo,mats[p.material]||new THREE.MeshStandardMaterial({morphTargets:true}));obj.name=m.name||'';if(p.targets){obj.updateMorphTargets();const names=(m.extras&&m.extras.targetNames)||p.targets.map((_,i)=>'target_'+i);obj.morphTargetDictionary={};names.forEach((name,i)=>obj.morphTargetDictionary[name]=i);(m.weights||[]).forEach((w,i)=>obj.morphTargetInfluences[i]=w);}obj.castShadow=true;obj.receiveShadow=true;return obj;}));
 const nodes=(g.nodes||[]).map((n,i)=>{const o=n.mesh!==undefined?new THREE.Group():new THREE.Bone();o.name=n.name||'node_'+i;if(n.matrix){o.matrix.fromArray(n.matrix);o.matrix.decompose(o.position,o.quaternion,o.scale);}else{if(n.translation)o.position.fromArray(n.translation);if(n.rotation)o.quaternion.fromArray(n.rotation);if(n.scale)o.scale.fromArray(n.scale);}if(n.mesh!==undefined)meshes[n.mesh].forEach((m,j)=>{const obj=m.clone();obj.name=m.name||o.name+'_mesh_'+j;o.add(obj);});return o;});
 (g.nodes||[]).forEach((n,i)=>(n.children||[]).forEach(j=>nodes[i].add(nodes[j])));
 // Attach scene roots and update matrices BEFORE binding skinned meshes.
 // glTF inverse bind matrices refer to rest-pose world transforms.
 const root=new THREE.Group(),scene=g.scenes&&g.scenes[g.scene||0],roots=scene?scene.nodes:nodes.map((_,i)=>i).filter(i=>!(g.nodes||[]).some(n=>(n.children||[]).includes(i)));
 roots.forEach(i=>root.add(nodes[i]));root.updateMatrixWorld(true);
 (g.nodes||[]).forEach((n,i)=>{if(n.skin===undefined)return;
   const skin=g.skins[n.skin],bones=skin.joints.map(j=>nodes[j]);
   let inverses=[];
   if(skin.inverseBindMatrices!==undefined){const arr=accessor(skin.inverseBindMatrices);for(let j=0;j<bones.length;j++)inverses.push(new THREE.Matrix4().fromArray(arr,j*16));}
   else inverses=bones.map(b=>b.matrixWorld.clone().invert());
   nodes[i].traverse(obj=>{if(obj.isSkinnedMesh){obj.bind(new THREE.Skeleton(bones,inverses),obj.matrixWorld);obj.normalizeSkinWeights();}});
 });
 const clips=(g.animations||[]).map((a,idx)=>{const tracks=[];a.channels.forEach(ch=>{const sampler=a.samplers[ch.sampler],target=ch.target,node=nodes[target.node];if(!node)return;const times=accessor(sampler.input),values=accessor(sampler.output),path=target.path,property={translation:'position',rotation:'quaternion',scale:'scale'}[path];if(!property||sampler.interpolation==='CUBICSPLINE')return;const Track=path==='rotation'?THREE.QuaternionKeyframeTrack:THREE.VectorKeyframeTrack;tracks.push(new Track(node.name+'.'+property,times,values));});return new THREE.AnimationClip(a.name||'clip_'+idx,-1,tracks);});
 return {scene:root,animations:clips,parserType:'offline-glb-morph-skin'};
}
THREE.GLTFLoader=function(){};
THREE.GLTFLoader.prototype.load=function(url,success,progress,error){
 // Android file:// WebViews commonly reject fetch(). Read the packaged asset
 // via the restricted Android bridge, without enabling universal file URL access.
 Promise.resolve().then(()=>{
  if(window.Android&&typeof Android.getBundledGlb==='function'){
   const b64=Android.getBundledGlb();if(!b64)throw Error('Bundled GLB asset unavailable');
   const raw=atob(b64),bytes=new Uint8Array(raw.length);
   for(let i=0;i<raw.length;i++)bytes[i]=raw.charCodeAt(i);
   return bytes.buffer;
  }
  return fetch(url).then(r=>{if(!r.ok)throw Error('HTTP '+r.status);return r.arrayBuffer();});
 }).then(b=>success(decode(b))).catch(e=>{console.error('GLB load failed',e);if(error)error(e);});
};
})();
