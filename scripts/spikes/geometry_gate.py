#!/usr/bin/env python3
"""Isolated OpenCascade feasibility gate; not an application runtime dependency."""
import json, math, time
from OCP.BRepPrimAPI import BRepPrimAPI_MakeBox, BRepPrimAPI_MakeCylinder, BRepPrimAPI_MakePrism
from OCP.BRepAlgoAPI import BRepAlgoAPI_Cut, BRepAlgoAPI_Fuse
from OCP.BRepBuilderAPI import BRepBuilderAPI_MakePolygon, BRepBuilderAPI_MakeFace, BRepBuilderAPI_MakeWire, BRepBuilderAPI_MakeEdge
from OCP.BRepCheck import BRepCheck_Analyzer
from OCP.BRepGProp import BRepGProp
from OCP.GProp import GProp_GProps
from OCP.BRepMesh import BRepMesh_IncrementalMesh
from OCP.GC import GC_MakeArcOfCircle
from OCP.gp import gp_Pnt, gp_Vec, gp_Ax2, gp_Dir

def box(x,y,z,origin=(0,0,0)):return BRepPrimAPI_MakeBox(gp_Pnt(*origin),x,y,z).Shape()
def cut(a,b):
    op=BRepAlgoAPI_Cut(a,b);op.Build();assert op.IsDone();return op.Shape()
def cylinder(r,h,origin,direction=(0,0,1)):
    return BRepPrimAPI_MakeCylinder(gp_Ax2(gp_Pnt(*origin),gp_Dir(*direction)),r,h).Shape()
def polygon(points,depth):
    wire=BRepBuilderAPI_MakePolygon()
    for p in points:wire.Add(gp_Pnt(*p,0))
    wire.Close();face=BRepBuilderAPI_MakeFace(wire.Wire()).Face()
    return BRepPrimAPI_MakePrism(face,gp_Vec(0,0,depth)).Shape()

def plate():
    shape=box(60,40,5)
    for x in (15,45):shape=cut(shape,cylinder(4,7,(x,20,-1)))
    return shape,(60*40-2*math.pi*16)*5

def concave():return polygon([(0,0),(30,0),(30,10),(10,10),(10,30),(0,30)],8),500*8

def tangent():
    a,b,c=gp_Pnt(0,0,0),gp_Pnt(10,10,0),gp_Pnt(20,0,0)
    arc=GC_MakeArcOfCircle(a,b,c).Value()
    # Arc endpoints have vertical tangents, adjoining two vertical segments.
    wire=BRepBuilderAPI_MakeWire()
    for edge in (BRepBuilderAPI_MakeEdge(gp_Pnt(0,-10,0),a).Edge(),BRepBuilderAPI_MakeEdge(arc).Edge(),
                 BRepBuilderAPI_MakeEdge(c,gp_Pnt(20,-10,0)).Edge(),BRepBuilderAPI_MakeEdge(gp_Pnt(20,-10,0),gp_Pnt(0,-10,0)).Edge()):wire.Add(edge)
    face=BRepBuilderAPI_MakeFace(wire.Wire()).Face()
    return BRepPrimAPI_MakePrism(face,gp_Vec(0,0,5)).Shape(),(200+50*math.pi)*5

def profile():
    shape=cut(box(60,40,300),box(54,34,302,(3,3,-1)))
    for z in (60,120,180):shape=cut(shape,cylinder(6,62,(-1,20,z),(1,0,0)))
    return shape,(60*40-54*34)*300-18*math.pi*36

def plates():
    op=BRepAlgoAPI_Fuse(box(60,40,5),box(60,40,5,(0,0,4.999999)));op.Build();assert op.IsDone()
    return op.Shape(),60*40*9.999999

results=[]
for name,fixture in [('plate-with-two-holes',plate),('concave-contour',concave),('tangent-arc',tangent),('cross-drilled-hollow-profile',profile),('near-coplanar-plates',plates)]:
    started=time.perf_counter();shape,expected=fixture()
    assert BRepCheck_Analyzer(shape).IsValid(),name
    properties=GProp_GProps();BRepGProp.VolumeProperties_s(shape,properties)
    volume=properties.Mass();assert abs(volume-expected)<=max(.001,expected*1e-7),(name,volume,expected)
    tessellation=BRepMesh_IncrementalMesh(shape,.1,False,.2,False);assert tessellation.IsDone(),name
    results.append(dict(fixture=name,valid=True,volume=volume,expected=expected,elapsedMs=(time.perf_counter()-started)*1000))
print(json.dumps(dict(kernel='OpenCascade/OCP 8.0.1.1.0',fixtures=results),indent=2))

if '--stress' in __import__('sys').argv:
    print('READY',flush=True)
    for _ in range(100000):profile()
