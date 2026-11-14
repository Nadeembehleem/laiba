(function(){'use strict';
const info=document.getElementById('info');let scene,camera,renderer,model,controls,mixer,clock,accent,avatarRoot;
let action='idle',actionTime=0,rgb=false,speakingUntil=0,blinkAt=2,eyeNodes=[],faceMeshes=[],arms=[],clips=[];
function status(s){info.textContent=s;try{Android.onAvatarStatus(s)}catch(e){}}
function fallback(){const group=new THREE.Group();const skin=new THREE.MeshStandardMaterial({color:0xe8bca8,roughness:.8}),dress=new THREE.MeshStandardMaterial({color:0x80b9ef,roughness:.8}),hair=new THREE.MeshStandardMaterial({color:0x39231c,roughness:.8});function part(g,m,x,y,z){const o=new THREE.Mesh(g,m);o.position.set(x,y,z);group.add(o);return o;}part(new THREE.SphereGeometry(.20,24,18),skin,0,1.54,0);part(new THREE.SphereGeometry(.23,24,18),hair,0,1.66,-.06);part(new THREE.CylinderGeometry(.20,.34,.75,24),dress,0,.95,0);for(const x of [-.17,.17])part(new THREE.CylinderGeometry(.085,.07,.65,12),skin,x,.34,0);for(const x of [-.30,.30])part(new THREE.CylinderGeometry(.065,.055,.64,12),skin,x,1.05,0);return group;}
function refineMaterials(){
  // Keep original mesh topology and rig; tune PBR to avoid the blown-out white look.
  const visited=new Set();
  model.traverse(o=>{if(!o.isMesh)return;
    o.castShadow=false;
    const mats=Array.isArray(o.material)?o.material:[o.material];
    for(const mat of mats){if(!mat||visited.has(mat))continue;visited.add(mat);
      const n=(mat.name||'').toLowerCase();
      if(/dress|chiffon|floral|trim/.test(n)){mat.roughness=.91;mat.metalness=0;}
      else if(/skin|lip/.test(n)){mat.roughness=.86;mat.metalness=0;}
      else if(/hair/.test(n)){mat.roughness=.78;mat.metalness=0;}
      else if(/eye/.test(n)){mat.roughness=.37;mat.metalness=0;}
      if(mat.emissive&&mat.emissiveIntensity)mat.emissiveIntensity=Math.min(mat.emissiveIntensity,.08);
      mat.needsUpdate=true;
    }
  });
}
function findFeatures(){eyeNodes=[];faceMeshes=[];arms=[];model.traverse(o=>{const n=(o.name||'').toLowerCase();if(/eye|pupil/.test(n))eyeNodes.push(o);if(o.isMesh&&o.morphTargetInfluences&&o.morphTargetDictionary)faceMeshes.push(o);if(/upper.?arm|shoulder|arm[_ .-]?(l|r|left|right)/.test(n)&&o.isBone)arms.push(o);});}
function morph(regex,v){for(const o of faceMeshes)for(const [name,i] of Object.entries(o.morphTargetDictionary))if(regex.test(name.toLowerCase()))o.morphTargetInfluences[i]=v;}
function startAction(name){action=name;actionTime=0;if(name==='rgb')rgb=!rgb; if(mixer&&clips.length&&/wave|dance/.test(name)){const clip=clips.find(c=>new RegExp(name,'i').test(c.name));if(clip){mixer.stopAllAction();const a=mixer.clipAction(clip);a.reset().play();}}}
try{
scene=new THREE.Scene();camera=new THREE.PerspectiveCamera(36,1,.01,100);camera.position.set(0,1.1,3.8);
renderer=new THREE.WebGLRenderer({antialias:true,alpha:true});renderer.setPixelRatio(Math.min(devicePixelRatio||1,1.6));renderer.outputEncoding=THREE.sRGBEncoding;renderer.toneMapping=THREE.ACESFilmicToneMapping;renderer.toneMappingExposure=.78;document.body.appendChild(renderer.domElement);
scene.add(new THREE.HemisphereLight(0xe8e3ff,0x3a2450,.72));const key=new THREE.DirectionalLight(0xffeee4,.82);key.position.set(-2,3,3);scene.add(key);const fill=new THREE.DirectionalLight(0x9acbff,.27);fill.position.set(2,1,1);scene.add(fill);accent=new THREE.PointLight(0xa47bff,.35,10);accent.position.set(1,2,2);scene.add(accent);
clock=new THREE.Clock();if(THREE.OrbitControls){controls=new THREE.OrbitControls(camera,renderer.domElement);controls.target.set(0,1,0);controls.enablePan=false;controls.enableDamping=true;controls.update();}
function resize(){const w=innerWidth,h=innerHeight;camera.aspect=w/h;camera.updateProjectionMatrix();renderer.setSize(w,h);}addEventListener('resize',resize);resize();
model=fallback();scene.add(model);status('Loading 3D avatar…');
const loader=new THREE.GLTFLoader();
function loadAvatar(url, allowFallback){loader.load(url,g=>{try{if(!g||!g.scene)throw Error('Missing GLB scene');scene.remove(model);avatarRoot=new THREE.Group();model=g.scene;const box=new THREE.Box3().setFromObject(model),size=box.getSize(new THREE.Vector3()),center=box.getCenter(new THREE.Vector3());const scale=2.05/Math.max(size.y,.01);model.scale.setScalar(scale);model.position.set(-center.x*scale,-box.min.y*scale,-center.z*scale);avatarRoot.add(model);scene.add(avatarRoot);refineMaterials();findFeatures();clips=g.animations||[];if(g.animations&&g.animations.length){mixer=new THREE.AnimationMixer(model);mixer.clipAction(g.animations.find(c=>/idle|breath/i.test(c.name))||g.animations[0]).play();}status('3D avatar loaded • V28 model pipeline ready');}catch(e){status('Avatar error: '+e.message)}},undefined,e=>{if(allowFallback){status('New avatar not installed; loading existing model…');loadAvatar('layaba_realistic.glb',false);}else status('Avatar preview: '+(e.message||e));});}
loadAvatar('laiba_human_v28.glb',true);
let elapsed=0;function animate(){requestAnimationFrame(animate);const dt=Math.min(clock.getDelta(),.06);elapsed+=dt;actionTime+=dt;if(mixer)mixer.update(dt);
const root=avatarRoot||model;if(root){root.position.y=.012*Math.sin(elapsed*1.6);if(action==='dance'){root.rotation.y=.24*Math.sin(actionTime*3);root.rotation.z=.08*Math.sin(actionTime*5);root.position.y+=.06*Math.abs(Math.sin(actionTime*5));}else{root.rotation.y*=.91;root.rotation.z*=.9;}
if(action==='wave'){const a=arms.find(o=>/right|[_ .-]r\b/.test(o.name.toLowerCase()))||arms[0];if(a)a.rotation.z=-.45+.3*Math.sin(actionTime*8);else root.rotation.z=.08*Math.sin(actionTime*8);}if(actionTime>5&&action!=='rgb'){if(action!=='idle'&&mixer&&clips.length){const idle=clips.find(c=>/idle|breath/i.test(c.name));if(idle){mixer.stopAllAction();mixer.clipAction(idle).reset().play();}}action='idle';}}
const speaking=performance.now()<speakingUntil;morph(/mouthopen|jawopen|viseme|mouth_open|aa|openmouth/,speaking?.25+.22*Math.abs(Math.sin(elapsed*13)):0);
if(elapsed>blinkAt){morph(/blink|eyesclosed/,1);if(elapsed>blinkAt+.13){morph(/blink|eyesclosed/,0);blinkAt=elapsed+2.5+Math.random()*3;}}
if(rgb)accent.color.setHSL((elapsed*.14)%1,.85,.64);else accent.color.setHex(0xa47bff);
if(controls)controls.update();renderer.render(scene,camera);}animate();
}catch(e){status('WebGL unavailable: '+e.message)}
window.Laiba={command:function(c){if(c==='face'){camera.position.set(0,1.55,1.7);if(controls)controls.target.set(0,1.5,0);}else if(c==='full'){camera.position.set(0,1.1,3.8);if(controls)controls.target.set(0,1,0);}else startAction(c);if(controls)controls.update();},speak:function(durationMs){speakingUntil=performance.now()+Math.max(400,Math.min(Number(durationMs)||1800,15000));},stopSpeaking:function(){speakingUntil=0;}};
})();
