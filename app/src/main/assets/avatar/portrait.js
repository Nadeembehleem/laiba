/* LAIBA realistic portrait avatar (2.5D): the reference photo is rendered as a depth-displaced mesh with
   shader-based blink, lip movement, wave, dance, RGB lighting, parallax and breathing. Exposes the same
   window.Laiba API as viewer.js (command / speak / stopSpeaking). */
(function(){'use strict';
var info=document.getElementById('info');
function status(s){try{info.textContent=s}catch(e){}try{Android.onAvatarStatus(s)}catch(e){}}
var PW=988,PH=845;                         // plate size in pixels
function px(x,y){return 'vec2('+(x-90).toFixed(1)+','+y.toFixed(1)+')'}  // original-image coords -> plate px
var VERT=[
'uniform sampler2D depthMap;uniform float depthScale;varying vec2 vUv;',
'void main(){vUv=uv;float d=texture2D(depthMap,uv).r-0.30;vec3 p=position;p.z+=d*depthScale;',
'gl_Position=projectionMatrix*modelViewMatrix*vec4(p,1.0);}'].join('\n');
var FRAG=[
'precision highp float;uniform sampler2D map;uniform float blink,mouth,wave,rgb,hue,time;varying vec2 vUv;',
'const vec2 PS=vec2('+PW+'.,'+PH+'.);',
'vec3 hsv(float h,float s,float v){vec3 k=clamp(abs(mod(h*6.+vec3(0.,4.,2.),6.)-3.)-1.,0.,1.);return v*mix(vec3(1.),k,s);}',
'float ell(vec2 p,vec2 c,vec2 r,float a,float b){return 1.-smoothstep(a,b,length((p-c)/r));}',
'vec3 lid(vec3 c,vec2 p,vec2 e){float m=ell(p,e+vec2(0.,2.),vec2(44.,27.),0.40,1.0)*smoothstep(e.y-27.,e.y-16.,p.y);float edge=e.y-15.+blink*33.;',
'vec3 skin=texture2D(map,vec2(p.x/PS.x,1.-(e.y+34.)/PS.y)).rgb;skin=mix(vec3(dot(skin,vec3(.3,.59,.11))),skin,0.65)*1.03;float cover=m*smoothstep(edge+1.5,edge-1.5,p.y)*smoothstep(0.02,0.12,blink);',
'c=mix(c,skin*0.97,cover);float line=m*smoothstep(3.0,0.0,abs(p.y-edge))*smoothstep(0.05,0.3,blink);return mix(c,vec3(0.11,0.05,0.05),line*0.85);}',
'void main(){',
' vec2 p=vec2(vUv.x,1.-vUv.y)*PS;vec2 q=p;',
// wave: rotate the raised hand about the wrist
' vec2 wp='+px(300,565)+';float wm=ell(p,'+px(310,440)+',vec2(150.,190.),0.45,1.0);',
' float ang=wave*sin(time*9.0)*0.17*wm;vec2 d=q-wp;float ca=cos(ang),sa=sin(ang);q=wp+vec2(ca*d.x+sa*d.y,-sa*d.x+ca*d.y);',
' vec2 eL='+px(562,230)+';vec2 eR='+px(662,253)+';',
// mouth: lower lip and chin move down
' vec2 mc='+px(593,337)+';float mw=ell(p,mc,vec2(62.,52.),0.4,1.0);float low=smoothstep(-4.,10.,p.y-mc.y+3.);',
' q.y-=mouth*15.*mw*low;q.x+=mouth*0.0;',
' vec2 uv2=vec2(q.x/PS.x,1.-q.y/PS.y);vec3 c=texture2D(map,uv2).rgb;',
' c=lid(c,p,eL);c=lid(c,p,eR);',
// dark mouth interior while open
' float gap=ell(p,mc+vec2(0.,3.+mouth*6.),vec2(24.+mouth*6.,1.5+mouth*10.),0.35,1.0)*smoothstep(0.03,0.2,mouth);',
' c=mix(c,vec3(0.20,0.035,0.05),gap*0.9);',
// RGB lighting: recolour the purple neon
' float pur=smoothstep(0.10,0.28,c.b-c.g)*smoothstep(0.25,0.6,c.b);',
' vec3 tint=hsv(hue,0.85,1.0);c=mix(c,c*0.35+tint*(0.25+c.b*1.1),pur*rgb*0.85);',
// feathered edges so the plate melts into the scene background
' float a=smoothstep(0.0,0.07,vUv.x)*smoothstep(1.0,0.93,vUv.x)*smoothstep(0.0,0.20,vUv.y)*smoothstep(0.0,0.05,1.0-vUv.y);',
' gl_FragColor=vec4(c*a,a);}'].join('\n');

var scene,camera,renderer,mesh,grp,mat,clock=new THREE.Clock();
var st={blink:0,blinkAt:2.2,blinkT:-1,mouth:0,speakUntil:0,action:'idle',actionT:0,rgb:0,rgbOn:false,
        zoom:1,zoomTarget:1,focusV:0,tx:0,ty:0,gx:0,gy:0,drag:false,lx:0,ly:0};
function layout(){
 var w=innerWidth,h=innerHeight,a=w/h;renderer.setSize(w,h);camera.aspect=a;camera.updateProjectionMatrix();
 var H=2*camera.position.z*Math.tan(camera.fov*Math.PI/360);
 var ph=Math.max(.5*H,Math.min(.66*H,(a*H/0.72)/(PW/PH)));
 grp.userData.base={ph:ph,y:.13*H,H:H};
 mesh.scale.set(ph*PW/PH,ph,1);
}
function loadTex(name,mime,cb){
 var b64='';try{b64=Android.getAsset(name)}catch(e){}
 if(!b64){status('Portrait asset missing: '+name);return}
 var img=new Image();img.onload=function(){var t=new THREE.Texture(img);t.minFilter=THREE.LinearFilter;t.magFilter=THREE.LinearFilter;
  t.generateMipmaps=false;t.wrapS=t.wrapT=THREE.ClampToEdgeWrapping;t.needsUpdate=true;cb(t)};
 img.onerror=function(){status('Could not decode '+name)};img.src='data:'+mime+';base64,'+b64;
}
function init(){
 scene=new THREE.Scene();camera=new THREE.PerspectiveCamera(30,1,.1,50);camera.position.set(0,0,3);
 renderer=new THREE.WebGLRenderer({antialias:true,alpha:true});renderer.setPixelRatio(Math.min(devicePixelRatio||1,2));
 renderer.setClearColor(0x000000,0);document.body.appendChild(renderer.domElement);
 grp=new THREE.Group();scene.add(grp);
 var vt=renderer.capabilities.maxVertexTextures>0;
 mat=new THREE.ShaderMaterial({vertexShader:VERT,fragmentShader:FRAG,transparent:true,premultipliedAlpha:true,depthWrite:false,
  uniforms:{map:{value:null},depthMap:{value:null},depthScale:{value:vt?.22:0},blink:{value:0},mouth:{value:0},wave:{value:0},rgb:{value:0},hue:{value:0},time:{value:0}}});
 mesh=new THREE.Mesh(new THREE.PlaneGeometry(1,1,96,82),mat);grp.add(mesh);
 addEventListener('resize',layout);layout();
 var need=2;function done(){if(--need===0){status('Realistic avatar ready');}}
 loadTex('laiba_portrait.jpg','image/jpeg',function(t){mat.uniforms.map.value=t;done()});
 loadTex('laiba_depth.png','image/png',function(t){mat.uniforms.depthMap.value=t;done()});
 // touch / mouse look-around (HUD controls do not hit the canvas)
 var cv=renderer.domElement;
 cv.addEventListener('pointerdown',function(e){st.drag=true;st.lx=e.clientX;st.ly=e.clientY});
 addEventListener('pointerup',function(){st.drag=false;});
 addEventListener('pointermove',function(e){if(!st.drag)return;st.tx=Math.max(-1,Math.min(1,st.tx+(e.clientX-st.lx)/160));st.ty=Math.max(-1,Math.min(1,st.ty+(e.clientY-st.ly)/220));st.lx=e.clientX;st.ly=e.clientY});
 try{addEventListener('deviceorientation',function(e){if(e.gamma==null)return;st.gx=Math.max(-1,Math.min(1,e.gamma/30));st.gy=Math.max(-1,Math.min(1,(e.beta-50)/40))})}catch(e){}
 animate();
}
function animate(){
 requestAnimationFrame(animate);
 var dt=Math.min(clock.getDelta(),.05),t=clock.elapsedTime;st.actionT+=dt;
 var now=performance.now(),speaking=now<st.speakUntil;
 // blink
 if(st.blinkT<0&&t>st.blinkAt){st.blinkT=0}
 if(st.blinkT>=0){st.blinkT+=dt;var k=st.blinkT/.18;st.blink=k<.5?k*2:Math.max(0,2-k*2);if(k>=1){st.blink=0;st.blinkT=-1;st.blinkAt=t+2.2+Math.random()*3.8;if(Math.random()<.2)st.blinkAt=t+.35}}
 if(st.forceBlink!=null)st.blink=st.forceBlink;else if(st.blinkT<0)st.blink=0;
 // mouth
 var target=speaking?(.28+.45*Math.abs(Math.sin(t*12.5))*(.6+.4*Math.abs(Math.sin(t*4.1+1))) ):0;
 st.mouth+=(target-st.mouth)*Math.min(1,dt*18);
 // action timeline
 var wv=0,dance=0;
 if(st.action==='wave'){wv=Math.min(1,st.actionT*3)*(st.actionT>4.2?Math.max(0,1-(st.actionT-4.2)*3):1);if(st.actionT>5)st.action='idle'}
 if(st.action==='dance'){dance=Math.min(1,st.actionT*2)*(st.actionT>7?Math.max(0,1-(st.actionT-7)*2):1);if(st.actionT>8)st.action='idle'}
 if(st.rgbOn)st.rgb=Math.min(1,st.rgb+dt*2);else st.rgb=Math.max(0,st.rgb-dt*2);
 var u=mat.uniforms;u.blink.value=st.blink;u.mouth.value=st.mouth;u.wave.value=wv;u.rgb.value=st.rgb;u.hue.value=(t*.12)%1;u.time.value=t;
 // zoom (full <-> face)
 st.zoom+=(st.zoomTarget-st.zoom)*Math.min(1,dt*5);
 var B=grp.userData.base,sc=st.zoom;
 // face point in mesh-local units (origin centre, +y up): plate px (506,270)
 var fx=(506/PW-.5)*(B.ph*PW/PH),fy=(.5-270/PH)*B.ph;
 var fz=(st.zoom-1)/(1.4);                    // 0 when full view, 1 when face view
 grp.scale.setScalar(sc);
 grp.position.set(-fx*sc*fz,B.y-(fy*sc)*fz+ (1-fz)*0 - .06*B.H*fz,0);
 // head-look / parallax
 var ix=st.tx+st.gx+.25*Math.sin(t*.45),iy=st.ty+st.gy*.6+.12*Math.sin(t*.37+1);
 grp.rotation.y+=((ix*.17+dance*.2*Math.sin(t*3.2))-grp.rotation.y)*Math.min(1,dt*6);
 grp.rotation.x+=((iy*.08)-grp.rotation.x)*Math.min(1,dt*6);
 grp.rotation.z=dance*.05*Math.sin(t*3.2+1);
 var breathe=1+.0045*Math.sin(t*1.7)+dance*.012*Math.abs(Math.sin(t*6.4));
 mesh.scale.y=B.ph*breathe;
 if(!st.drag){st.tx*=.985;st.ty*=.985}
 renderer.render(scene,camera);
}
window.Laiba={
 command:function(c){
  if(c==='face'){st.zoomTarget=2.4}
  else if(c==='full'){st.zoomTarget=1}
  else if(c==='wave'){st.action='wave';st.actionT=0}
  else if(c==='dance'){st.action='dance';st.actionT=0}
  else if(c==='rgb'){st.rgbOn=!st.rgbOn}
 },
 speak:function(ms){st.speakUntil=performance.now()+Math.max(400,Math.min(Number(ms)||1800,60000))},
 stopSpeaking:function(){st.speakUntil=0},_st:st
};
try{status('Loading realistic avatar…');init()}catch(e){status('WebGL unavailable: '+e.message)}
})();
