"""Run with Blender 4.x: blender -b -P create_laiba.py
Creates an original stylized human prototype with armature and idle/wave actions.
This is NOT a photorealistic scan or likeness reconstruction.
"""
import bpy, math, os
from mathutils import Vector
bpy.ops.object.select_all(action='SELECT'); bpy.ops.object.delete(use_global=False)

def material(name, color, roughness=.65):
 m=bpy.data.materials.new(name); m.diffuse_color=(*color,1); m.use_nodes=True
 bs=m.node_tree.nodes.get('Principled BSDF'); bs.inputs['Base Color'].default_value=(*color,1); bs.inputs['Roughness'].default_value=roughness
 return m
skin=material('Skin warm natural',(.72,.46,.38)); hair=material('Chestnut brown hair',(.19,.075,.047)); dress=material('Blue floral fabric base',(.48,.72,.86)); white=material('Eye white',(.92,.89,.86)); iris=material('Brown iris',(.18,.09,.06)); lip=material('Natural lip',(.62,.25,.31)); shoe=material('Shoes',(.19,.13,.16))

def sphere(name, loc, scale, mat, segments=32, rings=20):
 bpy.ops.mesh.primitive_uv_sphere_add(segments=segments,ring_count=rings,location=loc); o=bpy.context.object; o.name=name; o.scale=scale; bpy.ops.object.transform_apply(location=False,rotation=False,scale=True); o.data.materials.append(mat); return o

def tapered(name,z0,z1,r0,r1,mat,loc=(0,0)):
 bpy.ops.mesh.primitive_cone_add(vertices=48,radius1=r0,radius2=r1,depth=z1-z0,location=(loc[0],loc[1],(z0+z1)/2)); o=bpy.context.object;o.name=name;o.data.materials.append(mat);return o

def link(name,a,b,r,mat):
 a,b=Vector(a),Vector(b); mid=(a+b)/2; bpy.ops.mesh.primitive_cone_add(vertices=20,radius1=r,radius2=r*.87,depth=(b-a).length,location=mid);o=bpy.context.object;o.name=name;o.rotation_euler=(b-a).to_track_quat('Z','Y').to_euler();o.data.materials.append(mat);return o
# Proportioned adult human prototype, approx 1.75m tall.
tapered('Dress fitted torso',.90,1.44,.24,.18,dress)
tapered('Dress flowing skirt',.50,.96,.41,.22,dress)
sphere('Neck',(0,0,1.47),(.072,.07,.12),skin)
sphere('Head',(0,-.015,1.64),(.16,.13,.205),skin)
sphere('Left ear',(-.157,-.005,1.63),(.034,.025,.063),skin)
sphere('Right ear',(.157,-.005,1.63),(.034,.025,.063),skin)
sphere('Hair crown',(0,.018,1.80),(.169,.143,.095),hair)
# Eye details face toward -Y.
for s,label in [(-1,'L'),(1,'R')]:
 sphere(label+' sclera',(s*.068,-.128,1.685),(.043,.014,.027),white)
 sphere(label+' iris',(s*.068,-.141,1.683),(.018,.008,.020),iris)
 sphere(label+' eyebrow',(s*.068,-.137,1.731),(.052,.009,.009),hair)
 # Individual sweeping hair sections
 for j in range(7):
  x=s*(.12+j*.012); z=1.77-j*.012
  link(label+' hair strand '+str(j),(x,.015,z),(s*(.19+j*.008),.02,.90-j*.012),.010,hair)
sphere('Nose',(0,-.143,1.626),(.027,.035,.054),skin)
sphere('Lips',(0,-.144,1.566),(.050,.011,.013),lip)
for s,label in [(-1,'L'),(1,'R')]:
 link(label+' upper arm',(s*.205,0,1.37),(s*.26,0,1.12),.070,dress)
 link(label+' forearm',(s*.26,0,1.12),(s*.26,-.035,.90),.045,skin)
 sphere(label+' hand',(s*.26,-.035,.865),(.049,.038,.078),skin)
 link(label+' leg',(s*.13,0,.55),(s*.13,0,.14),.084,skin)
 sphere(label+' shoe',(s*.13,-.05,.085),(.095,.15,.075),shoe)
# Armature with human-readable joints; prototype geometry is not yet deform-weighted.
bpy.ops.object.armature_add(enter_editmode=True,location=(0,0,0)); rig=bpy.context.object;rig.name='LAIBA_Human_Rig'; ed=rig.data.edit_bones; ed.remove(ed[0])
def bone(name,head,tail,parent=None):
 b=ed.new(name);b.head=head;b.tail=tail
 if parent:b.parent=ed[parent]
 return b
bone('hips',(0,0,.92),(0,0,1.03));bone('spine',(0,0,1.03),(0,0,1.37),'hips');bone('neck',(0,0,1.37),(0,0,1.52),'spine');bone('head',(0,0,1.52),(0,0,1.78),'neck')
for s,tag in [(-1,'L'),(1,'R')]:
 bone('upper_arm.'+tag,(s*.18,0,1.37),(s*.26,0,1.13),'spine');bone('forearm.'+tag,(s*.26,0,1.13),(s*.26,-.035,.90),'upper_arm.'+tag);bone('hand.'+tag,(s*.26,-.035,.90),(s*.26,-.035,.82),'forearm.'+tag)
 bone('thigh.'+tag,(s*.13,0,.92),(s*.13,0,.52),'hips');bone('shin.'+tag,(s*.13,0,.52),(s*.13,0,.15),'thigh.'+tag)
bpy.ops.object.mode_set(mode='OBJECT')
# Parent visible parts to nearest bones for rigid motion. Smooth skin weights are a later stage.
for obj in list(bpy.context.scene.objects):
 if obj.type!='MESH':continue
 n=obj.name
 if 'upper arm' in n: target='upper_arm.'+('L' if n.startswith('L') else 'R')
 elif 'forearm' in n or 'hand' in n:target='forearm.'+('L' if n.startswith('L') else 'R')
 elif 'leg' in n or 'shoe' in n:target='shin.'+('L' if n.startswith('L') else 'R')
 elif 'Head' in n or 'Hair' in n or 'ear' in n or 'eye' in n or 'iris' in n or 'sclera' in n or 'Nose' in n or 'Lips' in n or 'eyebrow' in n or 'hair strand' in n:target='head'
 else:target='spine'
 world=obj.matrix_world.copy();obj.parent=rig;obj.parent_type='BONE';obj.parent_bone=target;obj.matrix_world=world
# Two exported animation actions.
def animate(name, wave=False):
 act=bpy.data.actions.new(name);rig.animation_data_create();rig.animation_data.action=act
 for frame in [1,18,36,54,72]:
  bpy.context.scene.frame_set(frame)
  for pb in rig.pose.bones:pb.rotation_mode='XYZ';pb.rotation_euler=(0,0,0)
  rig.pose.bones['spine'].rotation_euler[1]=.025*math.sin(frame*math.pi/36)
  rig.pose.bones['head'].rotation_euler[2]=.04*math.sin(frame*math.pi/36)
  if wave:
   rig.pose.bones['upper_arm.R'].rotation_euler[1]=-.85
   rig.pose.bones['forearm.R'].rotation_euler[0]=-.7+.25*math.sin(frame*math.pi/18)
  for pb in rig.pose.bones:pb.keyframe_insert(data_path='rotation_euler',frame=frame,group=pb.name)
 act.use_fake_user=True;return act
idle=animate('LAIBA_Idle');wave=animate('LAIBA_Wave',True)
rig.animation_data.action=idle
bpy.context.scene.frame_end=72
out=os.path.join(os.path.dirname(os.path.abspath(__file__)),'laiba_human_prototype.glb')
bpy.ops.export_scene.gltf(filepath=out,export_format='GLB',export_animations=True,export_materials='EXPORT',export_apply=False)
print('Exported:',out)
