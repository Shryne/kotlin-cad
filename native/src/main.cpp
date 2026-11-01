#include <nlohmann/json.hpp>
#include <Eigen/Dense>
#include <BRepPrimAPI_MakeBox.hxx>
#include <BRepPrimAPI_MakeCylinder.hxx>
#include <BRepPrimAPI_MakeSphere.hxx>
#include <BRepPrimAPI_MakeCone.hxx>
#include <Geom_BezierCurve.hxx>
#include <TColgp_Array1OfPnt.hxx>
#include <gp_Circ.hxx>
#include <gp_Elips.hxx>
#include <BRepOffsetAPI_ThruSections.hxx>
#include <BOPAlgo_ArgumentAnalyzer.hxx>
#include <BRepPrimAPI_MakePrism.hxx>
#include <BRepBuilderAPI_MakePolygon.hxx>
#include <BRepBuilderAPI_MakeFace.hxx>
#include <BRepBuilderAPI_MakeWire.hxx>
#include <BRepBuilderAPI_MakeEdge.hxx>
#include <BRepBuilderAPI_Transform.hxx>
#include <BRepAlgoAPI_Cut.hxx>
#include <BRepAlgoAPI_Fuse.hxx>
#include <BRepAlgoAPI_Common.hxx>
#include <BRepFilletAPI_MakeFillet.hxx>
#include <BRepFilletAPI_MakeChamfer.hxx>
#include <BRepAdaptor_Curve.hxx>
#include <TopExp.hxx>
#include <TopTools_IndexedMapOfShape.hxx>
#include <gp_Ax1.hxx>
#include <BRepCheck_Analyzer.hxx>
#include <BRepBndLib.hxx>
#include <Bnd_Box.hxx>
#include <BRepExtrema_DistShapeShape.hxx>
#include <BRepBuilderAPI_Sewing.hxx>
#include <BRepBuilderAPI_MakeSolid.hxx>
#include <BRepLib.hxx>
#include <BRepGProp.hxx>
#include <GProp_GProps.hxx>
#include <BRepMesh_IncrementalMesh.hxx>
#include <BRep_Tool.hxx>
#include <TopExp_Explorer.hxx>
#include <TopoDS.hxx>
#include <TopoDS_Face.hxx>
#include <TopLoc_Location.hxx>
#include <Poly_Triangulation.hxx>
#include <GC_MakeArcOfCircle.hxx>
#include <Standard_Version.hxx>
#include <gp_Pnt.hxx>
#include <gp_Vec.hxx>
#include <gp_Ax2.hxx>
#include <gp_Dir.hxx>
#include <gp_Trsf.hxx>
#include <iostream>
#include <chrono>
#include <stdexcept>
#include <cmath>
#include <algorithm>
using json=nlohmann::json;
constexpr double pi=3.14159265358979323846;
void require(bool ok,const std::string& message) { if(!ok) throw std::invalid_argument(message); }
double scalar(const json& j) { require(j.is_number(),"Expected number"); double v=j.get<double>();require(std::isfinite(v)&&std::abs(v)<=1e9,"Number out of bounds");return v; }
double positive(const json& j) { double v=scalar(j);require(v>=1e-6,"Dimension must be positive");return v; }
gp_Pnt point(const json& j) { require(j.is_array()&&j.size()==3,"Expected XYZ point");return {scalar(j[0]),scalar(j[1]),scalar(j[2])}; }
TopoDS_Shape cut(const TopoDS_Shape& a,const TopoDS_Shape& b) { BRepAlgoAPI_Cut op(a,b);op.Build();require(op.IsDone(),"Cut failed");return op.Shape(); }
TopoDS_Shape fuse(const TopoDS_Shape& a,const TopoDS_Shape& b) { BRepAlgoAPI_Fuse op(a,b);op.Build();require(op.IsDone(),"Fuse failed");return op.Shape(); }
TopoDS_Shape box(double x,double y,double z,gp_Pnt origin={0,0,0}) { return BRepPrimAPI_MakeBox(origin,x,y,z).Shape(); }
TopoDS_Shape cylinder(double radius,double height,gp_Pnt origin={0,0,0},gp_Dir direction={0,0,1}) { return BRepPrimAPI_MakeCylinder(gp_Ax2(origin,direction),radius,height).Shape(); }
TopoDS_Wire wire(const json& points) {
    require(points.is_array()&&points.size()>=3&&points.size()<=4096,"Invalid polygon");BRepBuilderAPI_MakePolygon p;
    for(const auto& pt:points) p.Add(point(pt));p.Close();require(p.IsDone(),"Polygon could not close");return p.Wire();
}
TopoDS_Wire curveWire(const json& segments) {
    require(segments.is_array()&&!segments.empty()&&segments.size()<=512,"Expected 1 to 512 curve segments");
    BRepBuilderAPI_MakeWire builder;
    for(const auto& segment:segments) {
        auto kind=segment.at("kind").get<std::string>();TopoDS_Edge edge;
        if(kind=="line") edge=BRepBuilderAPI_MakeEdge(point(segment.at("start")),point(segment.at("end"))).Edge();
        else if(kind=="arc") { GC_MakeArcOfCircle arc(point(segment.at("start")),point(segment.at("mid")),point(segment.at("end")));require(arc.IsDone(),"Invalid circular arc");edge=BRepBuilderAPI_MakeEdge(arc.Value()).Edge(); }
        else if(kind=="circle") { auto n=point(segment.at("normal"));gp_Vec direction(n.X(),n.Y(),n.Z());require(direction.Magnitude()>1e-9,"Circle normal is zero");edge=BRepBuilderAPI_MakeEdge(gp_Circ(gp_Ax2(point(segment.at("center")),gp_Dir(direction)),positive(segment.at("radius")))).Edge(); }
        else if(kind=="bezier") {
            const auto& controls=segment.at("controlPoints");require(controls.is_array()&&controls.size()==4,"Cubic Bezier needs four control points");
            TColgp_Array1OfPnt points(1,4);for(int i=0;i<4;++i)points.SetValue(i+1,point(controls[i]));
            edge=BRepBuilderAPI_MakeEdge(new Geom_BezierCurve(points)).Edge();
        } else throw std::invalid_argument("Unsupported curve segment");
        builder.Add(edge);require(builder.IsDone(),"Curve segments must be connected in order");
    }
    auto result=builder.Wire();require(result.Closed(),"Curve wire must close");return result;
}
TopoDS_Shape loft(const json& j) {
    const auto& sections=j.at("sections");
    require(sections.is_array()&&sections.size()>=2&&sections.size()<=32,"Loft needs 2 to 32 ordered sections");
    BRepOffsetAPI_ThruSections builder(true,j.value("ruled",false),1e-7);
    // Four matching quarter edges preserve the user's seam and twist, even when radii cross.
    builder.CheckCompatibility(false);
    std::vector<gp_Pnt> centers;std::vector<gp_Vec> normals;double forward=0;
    for(const auto& section:sections) {
        auto c=point(section.at("center")),u=point(section.at("u")),n=point(section.at("normal"));
        double rx=positive(section.at("radiusX")),ry=positive(section.at("radiusY"));
        for(const auto& previous:centers) require(c.Distance(previous)>1e-5,"Loft section centers coincide: separate or remove duplicate sections");
        gp_Vec normal(n.X(),n.Y(),n.Z());normal.Normalize();
        if(!centers.empty()) {
            gp_Vec delta(centers.back(),c);double previous=delta.Dot(normals.back()),next=delta.Dot(normal);
            if(forward==0) forward=previous>=0?1:-1;
            require(forward*previous>1e-5&&forward*next>1e-5,"Loft sections double back or are tangent to the path: reorder sections or rotate their planes to follow the path");
        }
        centers.push_back(c);normals.push_back(normal);
        gp_Ax2 axes(c,gp_Dir(n.X(),n.Y(),n.Z()),gp_Dir(u.X(),u.Y(),u.Z()));
        double start=0;
        if(rx<ry) { axes.SetXDirection(axes.YDirection());start=-pi/2; }
        gp_Elips ellipse(axes,std::max(rx,ry),std::min(rx,ry));
        BRepBuilderAPI_MakeWire profile;
        for(int q=0;q<4;++q) profile.Add(BRepBuilderAPI_MakeEdge(ellipse,start+q*pi/2,start+(q+1)*pi/2).Edge());
        require(profile.IsDone(),"Loft profile failed: check radii and orientation");builder.AddWire(profile.Wire());
    }
    builder.Build();require(builder.IsDone(),"Loft failed: add intermediate sections or reduce rotation and taper");
    auto result=builder.Shape();
    require(!result.IsNull()&&result.ShapeType()==TopAbs_SOLID&&BRepCheck_Analyzer(result).IsValid(),"Loft is invalid: separate intersecting sections or reduce curvature/twist");
    for(TopExp_Explorer e(result,TopAbs_SHELL);e.More();e.Next()) require(BRep_Tool::IsClosed(e.Current()),"Loft is not watertight: adjust end sections");
    BOPAlgo_ArgumentAnalyzer check;check.SetShape1(result);check.SelfInterMode()=true;check.StopOnFirstFaulty()=true;check.Perform();
    require(!check.HasErrors()&&!check.HasFaulty(),"Loft self-intersects: reduce curvature/twist or increase section spacing");
    GProp_GProps volume;BRepGProp::VolumeProperties(result,volume);
    require(volume.Mass()>1e-9,"Loft has zero or inverted volume: check section order and orientation");
    return result;
}
// Select by measured curve midpoint, never by unstable tessellation/edge indices.
gp_Pnt edgeMidpoint(const TopoDS_Edge& edge) {
    BRepAdaptor_Curve curve(edge);
    return curve.Value((curve.FirstParameter()+curve.LastParameter())*.5);
}
TopTools_IndexedMapOfShape edgeMap(const TopoDS_Shape& s) {
    TopTools_IndexedMapOfShape edges;TopExp::MapShapes(s,TopAbs_EDGE,edges);return edges;
}
json edgeInfo(const TopoDS_Shape& s) {
    auto edges=edgeMap(s);json result=json::array();
    for(int i=1;i<=edges.Extent();++i) {
        auto edge=TopoDS::Edge(edges(i));if(BRep_Tool::Degenerated(edge)) continue;
        BRepAdaptor_Curve curve(edge);auto mid=edgeMidpoint(edge);
        GProp_GProps props;BRepGProp::LinearProperties(edge,props);
        auto xyz=[](const gp_Pnt& p){return json::array({p.X(),p.Y(),p.Z()});};
        result.push_back({{"at",xyz(mid)},{"start",xyz(curve.Value(curve.FirstParameter()))},
            {"end",xyz(curve.Value(curve.LastParameter()))},{"length",props.Mass()},
            {"curve",curve.GetType()==GeomAbs_Line?"line":curve.GetType()==GeomAbs_Circle?"circle":"other"}});
    }
    return result;
}
std::vector<TopoDS_Edge> selectedEdges(const TopoDS_Shape& s,const json& selectors) {
    require(selectors.is_array()&&!selectors.empty()&&selectors.size()<=128,"Select 1 to 128 edges");
    auto edges=edgeMap(s);std::vector<TopoDS_Edge> result;std::vector<int> used;
    for(const auto& selector:selectors) {
        auto at=point(selector.at("at"));double tolerance=selector.contains("tolerance")?positive(selector.at("tolerance")):.001;
        require(tolerance<=1,"Edge tolerance must not exceed 1 mm");int found=0;
        for(int i=1;i<=edges.Extent();++i) {
            auto e=TopoDS::Edge(edges(i));if(BRep_Tool::Degenerated(e)) continue;
            if(edgeMidpoint(e).Distance(at)<=tolerance) {require(found==0,"Ambiguous edge selector");found=i;}
        }
        require(found!=0,"Selected edge no longer exists; inspect the source shape again");
        require(std::find(used.begin(),used.end(),found)==used.end(),"Duplicate edge selector");
        used.push_back(found);result.push_back(TopoDS::Edge(edges(found)));
    }
    return result;
}
TopoDS_Shape shape(const json& j,int depth=0,int* budgetPtr=nullptr) {
    int localBudget=0;int& budget=budgetPtr?*budgetPtr:localBudget;
    require(depth<=32&&++budget<=512,"Shape complexity exceeded");const auto type=j.at("type").get<std::string>();TopoDS_Shape result;
    if(type=="box") result=box(positive(j.at("x")),positive(j.at("y")),positive(j.at("z")));
    else if(type=="cylinder") result=cylinder(positive(j.at("radius")),positive(j.at("height")));
    else if(type=="cone") {
        double a=scalar(j.at("radius1")),b=scalar(j.at("radius2"));require(a>=0&&b>=0&&std::max(a,b)>=1e-6&&std::abs(a-b)>=1e-8,"Invalid cone radii");
        result=BRepPrimAPI_MakeCone(a,b,positive(j.at("height"))).Shape();
    }
    else if(type=="loft") result=loft(j);
    else if(type=="sphere") result=BRepPrimAPI_MakeSphere(positive(j.at("radius"))).Shape();
    else if(type=="extrude"||type=="extrudeCurves") {
        bool curved=type=="extrudeCurves";auto outer=curved?curveWire(j.at("outer")):wire(j.at("outer"));BRepBuilderAPI_MakeFace face(outer,true);
        for(const auto& hole:j.value("holes",json::array())) { auto inner=curved?curveWire(hole):wire(hole);inner.Reverse();face.Add(inner); }
        require(face.IsDone(),"Invalid planar face");const auto vector=point(j.at("vector"));gp_Vec v(vector.X(),vector.Y(),vector.Z());require(v.Magnitude()>=1e-6,"Extrusion is zero");
        result=BRepPrimAPI_MakePrism(face.Face(),v).Shape();
    } else if(type=="cut"||type=="union"||type=="intersect") {
        auto a=shape(j.at("a"),depth+1,&budget),b=shape(j.at("b"),depth+1,&budget);
        if(type=="intersect") { BRepAlgoAPI_Common op(a,b);op.Build();require(op.IsDone(),"Intersection failed");result=op.Shape(); }
        else result=type=="cut"?cut(a,b):fuse(a,b);
    } else if(type=="fillet"||type=="chamfer") {
        auto input=shape(j.at("shape"),depth+1,&budget);auto edges=selectedEdges(input,j.at("edges"));
        double size=positive(j.at(type=="fillet"?"radius":"distance"));
        if(type=="fillet") { BRepFilletAPI_MakeFillet op(input);for(const auto& e:edges) op.Add(size,e);op.Build();require(op.IsDone(),"Fillet failed; reduce radius or change edges");result=op.Shape(); }
        else { BRepFilletAPI_MakeChamfer op(input);for(const auto& e:edges) op.Add(size,e);op.Build();require(op.IsDone(),"Chamfer failed; reduce distance or change edges");result=op.Shape(); }
    } else if(type=="rotate") {
        auto input=shape(j.at("shape"),depth+1,&budget);auto axis=point(j.at("axis"));gp_Vec v(axis.X(),axis.Y(),axis.Z());
        require(v.Magnitude()>1e-9,"Rotation axis is zero");
        gp_Trsf transform;transform.SetRotation(gp_Ax1(point(j.value("origin",json::array({0,0,0}))),gp_Dir(v)),scalar(j.at("angle")));
        result=BRepBuilderAPI_Transform(input,transform,true).Shape();
    } else if(type=="translate") {
        auto input=shape(j.at("shape"),depth+1,&budget);auto p=point(j.at("offset"));gp_Trsf transform;transform.SetTranslation(gp_Vec(p.X(),p.Y(),p.Z()));
        result=BRepBuilderAPI_Transform(input,transform,true).Shape();
    } else throw std::invalid_argument("Unsupported shape type");
    require(!result.IsNull()&&BRepCheck_Analyzer(result).IsValid(),"Invalid result shape");return result;
}
json tessellate(const TopoDS_Shape& s,double tolerance=.1) {
    BRepMesh_IncrementalMesh mesher(s,tolerance,false,.2,false);require(mesher.IsDone(),"Tessellation failed");
    json vertices=json::array(),indices=json::array(),faces=json::array();int faceId=0;
    for(TopExp_Explorer e(s,TopAbs_FACE);e.More();e.Next()) {
        auto face=TopoDS::Face(e.Current());TopLoc_Location location;auto triangulation=BRep_Tool::Triangulation(face,location);
        if(triangulation.IsNull()) continue;
        require(vertices.size()+triangulation->NbNodes()<=300000&&indices.size()/3+triangulation->NbTriangles()<=200000,"Mesh limit exceeded");
        const int offset=static_cast<int>(vertices.size()),start=static_cast<int>(indices.size()/3);
        for(int i=1;i<=triangulation->NbNodes();++i) { auto p=triangulation->Node(i).Transformed(location.Transformation());vertices.push_back({p.X(),p.Y(),p.Z()}); }
        for(int i=1;i<=triangulation->NbTriangles();++i) { int a,b,c;triangulation->Triangle(i).Get(a,b,c);if(face.Orientation()==TopAbs_REVERSED) std::swap(b,c);indices.push_back(offset+a-1);indices.push_back(offset+b-1);indices.push_back(offset+c-1); }
        faces.push_back({{"localId",faceId++},{"firstTriangle",start},{"triangleCount",triangulation->NbTriangles()}});
    }
    GProp_GProps p;BRepGProp::VolumeProperties(s,p);
    return {{"vertices",vertices},{"indices",indices},{"faces",faces},{"volume",p.Mass()},{"valid",true}};
}
json solidInfo(const TopoDS_Shape& s,bool single=false) {
    require(!s.IsNull()&&BRepCheck_Analyzer(s).IsValid(),"Invalid solid: check dimensions, Boolean operands and edge sizes");
    TopTools_IndexedMapOfShape solids;TopExp::MapShapes(s,TopAbs_SOLID,solids);
    require(solids.Extent()>0,"Construction produced no solid: check that cuts leave material and intersections overlap");
    require(!single||solids.Extent()==1,"Construction produced disconnected solids: create one feature per physical component or connect the operands");
    for(TopExp_Explorer e(s,TopAbs_SHELL);e.More();e.Next()) require(BRep_Tool::IsClosed(e.Current()),"Solid has an open shell; repair the profile");
    BOPAlgo_ArgumentAnalyzer check;check.SetShape1(s);check.SelfInterMode()=true;check.StopOnFirstFaulty()=true;check.Perform();
    require(!check.HasErrors()&&!check.HasFaulty(),"Solid self-intersects: check profile, Boolean operands or fillet size");
    GProp_GProps props;BRepGProp::VolumeProperties(s,props);require(props.Mass()>1e-9,"Solid volume is zero or inverted; check dimensions and orientation");
    Bnd_Box b;BRepBndLib::AddOptimal(s,b,false,false);double x,y,z,X,Y,Z;b.Get(x,y,z,X,Y,Z);
    auto xyz=[](double a,double b,double c){return json{{"x",a},{"y",b},{"z",c}};};
    return {{"validation",{{"valid",true},{"closed",true},{"solid",true},{"solidCount",solids.Extent()},{"selfIntersectionChecked",true}}},
        {"solidCount",solids.Extent()},{"volumeMm3",props.Mass()},{"bounds",{{"min",xyz(x,y,z)},{"max",xyz(X,Y,Z)}}},{"dimensions",xyz(X-x,Y-y,Z-z)}};
}
TopoDS_Shape interferenceBody(const json& input) {
    if(input.contains("shape")) return shape(input.at("shape"));
    const auto& mesh=input.at("mesh");const auto& vertices=mesh.at("vertices");const auto& indices=mesh.at("indices");
    require(indices.size()%3==0&&indices.size()/3<=10000,"Mesh-based interference limited to 10000 triangles per body; use native construction features for complex solids");
    BRepBuilderAPI_Sewing sewing(1e-7);
    for(size_t i=0;i<indices.size();i+=3) {
        BRepBuilderAPI_MakePolygon polygon;
        for(int k=0;k<3;++k) { int index=indices[i+k];require(index>=0&&index<vertices.size(),"Invalid mesh index");polygon.Add(point(vertices[index])); }
        polygon.Close();require(polygon.IsDone(),"Degenerate mesh triangle");sewing.Add(BRepBuilderAPI_MakeFace(polygon.Wire()).Face());
    }
    sewing.Perform();auto sewn=sewing.SewedShape();require(!sewn.IsNull(),"Mesh cannot be sewn into a solid");
    TopTools_IndexedMapOfShape shells;TopExp::MapShapes(sewn,TopAbs_SHELL,shells);require(shells.Extent()==1,"Mesh needs one connected closed shell for interference checking");
    auto shell=TopoDS::Shell(shells(1));require(BRep_Tool::IsClosed(shell),"Open mesh cannot be used for solid interference checking");
    auto solid=BRepBuilderAPI_MakeSolid(shell).Solid();require(BRepLib::OrientClosedSolid(solid),"Cannot orient mesh solid");return solid;
}
json interference(const json& request) {
    auto a=interferenceBody(request.at("a")),b=interferenceBody(request.at("b"));solidInfo(a);solidInfo(b);
    BRepAlgoAPI_Common common(a,b);common.Build();require(common.IsDone()&&!common.HasErrors(),"Interference Boolean failed; inspect the selected solids");
    GProp_GProps props;BRepGProp::VolumeProperties(common.Shape(),props);double volume=std::max(0.0,props.Mass());
    BRepExtrema_DistShapeShape distance(a,b);distance.Perform();require(distance.IsDone(),"Body distance evaluation failed");
    double contact=positive(request.value("contactToleranceMm",json(.00001)));
    double volumeTolerance=positive(request.value("volumeToleranceMm3",json(.000001)));
    double gap=distance.Value();std::string status=volume>volumeTolerance?"overlap":gap<=contact?"contact":"separated";
    require(distance.NbSolution()>0,"Body distance has no closest points");
    auto closestA=distance.PointOnShape1(1),closestB=distance.PointOnShape2(1);
    return {{"closestA",json::array({closestA.X(),closestA.Y(),closestA.Z()})},{"closestB",json::array({closestB.X(),closestB.Y(),closestB.Z()})},{"status",status},{"intersectionVolumeMm3",volume},{"distanceMm",gap},{"contactToleranceMm",contact},{"volumeToleranceMm3",volumeTolerance},
        {"method",request.at("a").contains("mesh")||request.at("b").contains("mesh")?"faceted-solid":"native-brep"}};
}
Eigen::VectorXd residuals(const Eigen::VectorXd& x,const json& constraints) {
    std::vector<double> rows;
    for(const auto& c:constraints) {
        auto ids=c.at("vars").get<std::vector<int>>();require(ids.size()<=8,"Too many constraint variables");
        for(int i:ids) require(i>=0&&i<x.size(),"Unknown variable");
        const auto t=c.at("type").get<std::string>();auto v=[&](int i){require(i<ids.size(),"Missing constraint variable");return x[ids[i]];};
        const double target=c.contains("target")?scalar(c["target"]):0;
        if(t=="fix") rows.push_back(v(0)-target);
        else if(t=="equal"||t=="horizontal"||t=="vertical") rows.push_back(v(0)-v(1));
        else if(t=="coincident"||t=="concentric") { rows.push_back(v(0)-v(2));rows.push_back(v(1)-v(3)); }
        else if(t=="distance") rows.push_back(std::hypot(v(2)-v(0),v(3)-v(1))-target);
        else if(t=="angle") rows.push_back(std::remainder(std::atan2(v(3)-v(1),v(2)-v(0))-target,2*pi));
        else if(t=="parallel"||t=="perpendicular"||t=="equalLength") {
            const double ax=v(2)-v(0),ay=v(3)-v(1),bx=v(6)-v(4),by=v(7)-v(5),la=std::hypot(ax,ay),lb=std::hypot(bx,by);
            require(la>1e-9&&lb>1e-9,"Degenerate line constraint");
            rows.push_back(t=="equalLength"?la-lb:(t=="parallel"?ax*by-ay*bx:ax*bx+ay*by)/(la*lb));
        } else if(t=="tangentLineCircle") {
            const double dx=v(2)-v(0),dy=v(3)-v(1),length=std::hypot(dx,dy);require(length>1e-9,"Degenerate tangent line");
            rows.push_back(std::abs(dx*(v(5)-v(1))-dy*(v(4)-v(0)))/length-v(6));
        } else if(t=="tangentCircles") rows.push_back(std::hypot(v(3)-v(0),v(4)-v(1))-(c.value("internal",false)?std::abs(v(2)-v(5)):v(2)+v(5)));
        else throw std::invalid_argument("Unsupported constraint type");
    }
    Eigen::VectorXd r(rows.size());for(int i=0;i<rows.size();++i) r[i]=rows[i];require(r.allFinite(),"Non-finite residual");return r;
}
Eigen::MatrixXd jacobian(const Eigen::VectorXd& x,const json& constraints) {
    const int m=residuals(x,constraints).size();Eigen::MatrixXd j(m,x.size());
    for(int k=0;k<x.size();++k) { auto plus=x,minus=x;double h=1e-6*(1+std::abs(x[k]));plus[k]+=h;minus[k]-=h;j.col(k)=(residuals(plus,constraints)-residuals(minus,constraints))/(2*h); }
    return j;
}
json solve(const json& request) {
    auto input=request.at("variables").get<std::vector<double>>();require(!input.empty()&&input.size()<=256,"Variable limit exceeded");
    for(double v:input) require(std::isfinite(v)&&std::abs(v)<=1e9,"Invalid solver input");
    const auto constraints=request.at("constraints");require(constraints.is_array()&&constraints.size()<=512,"Constraint limit exceeded");
    Eigen::VectorXd x=Eigen::Map<Eigen::VectorXd>(input.data(),input.size());double lambda=1e-3;int iterations=0;
    if(constraints.empty()) return {{"status","underconstrained"},{"variables",input},{"degreesOfFreedom",input.size()},{"conflicts",json::array()},{"residual",0}};
    for(;iterations<150;++iterations) {
        auto r=residuals(x,constraints);if(r.lpNorm<Eigen::Infinity>()<1e-7) break;
        auto j=jacobian(x,constraints);Eigen::MatrixXd normal=j.transpose()*j;normal.diagonal().array()+=lambda;
        Eigen::VectorXd delta=normal.ldlt().solve(-j.transpose()*r);require(delta.allFinite(),"Solver failed");
        Eigen::VectorXd candidate=x+delta;
        if(candidate.cwiseAbs().maxCoeff()>1e9) { lambda=std::min(lambda*10,1e10);continue; }
        if(residuals(candidate,constraints).squaredNorm()<r.squaredNorm()) { x=candidate;lambda=std::max(lambda*.3,1e-12); }
        else lambda=std::min(lambda*10,1e10);
    }
    const auto r=residuals(x,constraints);const bool valid=r.lpNorm<Eigen::Infinity>()<1e-7;
    Eigen::JacobiSVD<Eigen::MatrixXd> svd(jacobian(x,constraints));svd.setThreshold(1e-8);const int dof=static_cast<int>(x.size()-svd.rank());
    json conflicts=json::array();if(!valid) { int row=0;for(int i=0;i<constraints.size();++i) {
        auto t=constraints[i].at("type").get<std::string>();int count=(t=="coincident"||t=="concentric")?2:1;bool bad=false;
        for(int k=0;k<count;++k) bad|=std::abs(r[row++])>1e-7;if(bad) conflicts.push_back(constraints[i].value("id",std::to_string(i)));
    } }
    // Failed solves never return a replacement geometry.
    return {{"status",valid?(dof?"underconstrained":"fullyconstrained"):"conflicting"},
        {"variables",valid?std::vector<double>(x.data(),x.data()+x.size()):input},{"degreesOfFreedom",dof},
        {"conflicts",conflicts},{"residual",r.lpNorm<Eigen::Infinity>()},{"iterations",iterations}};
}
json selfTest() {
    json results=json::array();auto test=[&](const std::string& name,const TopoDS_Shape& s,double expected){
        auto start=std::chrono::steady_clock::now();require(BRepCheck_Analyzer(s).IsValid(),name+" invalid");auto mesh=tessellate(s);
        const double volume=mesh["volume"];require(std::abs(volume-expected)<=std::max(.001,std::abs(expected)*1e-7),name+" volume mismatch");
        results.push_back({{"fixture",name},{"valid",true},{"volume",volume},{"triangles",mesh["indices"].size()/3},
            {"elapsedMs",std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-start).count()}});
    };
    auto plate=cut(cut(box(60,40,5),cylinder(4,7,{15,20,-1})),cylinder(4,7,{45,20,-1}));test("plate-with-two-holes",plate,(2400-32*pi)*5);
    auto concave=shape({{"type","extrude"},{"outer",{{0,0,0},{30,0,0},{30,10,0},{10,10,0},{10,30,0},{0,30,0}}},{"vector",{0,0,8}}});test("concave-contour",concave,4000);
    BRepBuilderAPI_MakeWire tangent;
    tangent.Add(BRepBuilderAPI_MakeEdge(gp_Pnt(0,-10,0),gp_Pnt(0,0,0)).Edge());
    tangent.Add(BRepBuilderAPI_MakeEdge(GC_MakeArcOfCircle(gp_Pnt(0,0,0),gp_Pnt(10,10,0),gp_Pnt(20,0,0)).Value()).Edge());
    tangent.Add(BRepBuilderAPI_MakeEdge(gp_Pnt(20,0,0),gp_Pnt(20,-10,0)).Edge());tangent.Add(BRepBuilderAPI_MakeEdge(gp_Pnt(20,-10,0),gp_Pnt(0,-10,0)).Edge());
    test("tangent-arc",BRepPrimAPI_MakePrism(BRepBuilderAPI_MakeFace(tangent.Wire()).Face(),gp_Vec(0,0,5)).Shape(),(200+50*pi)*5);
    auto profile=cut(box(60,40,300),box(54,34,302,{3,3,-1}));for(int z:{60,120,180}) profile=cut(profile,cylinder(6,62,{-1,20,double(z)},{1,0,0}));
    test("cross-drilled-hollow-profile",profile,(2400-54*34)*300-18*pi*36);
    test("near-coplanar-plates",fuse(box(60,40,5),box(60,40,5,{0,0,4.999999})),2400*9.999999);
    auto fixed=[](int i,double v){return json{{"type","fix"},{"vars",{i}},{"target",v}};};
    json constraints=json::array();for(int i=0;i<4;++i) constraints.push_back(fixed(i,i==3?20:0));constraints.push_back(fixed(5,0));constraints.push_back(fixed(6,10));
    constraints.push_back({{"type","tangentLineCircle"},{"vars",{0,1,2,3,4,5,6}}});
    auto solved=solve({{"variables",{0,0,0,20,9,1,8}},{"constraints",constraints}});require(solved["status"]=="fullyconstrained"&&std::abs(solved["variables"][4].get<double>()-10)<1e-6,"Tangent solver failed");
    auto under=solve({{"variables",{0,5}},{"constraints",{fixed(0,10)}}});require(under["degreesOfFreedom"]==1,"DOF diagnosis failed");
    auto conflict=solve({{"variables",{7}},{"constraints",{fixed(0,20),fixed(0,30)}}});require(conflict["status"]=="conflicting"&&conflict["variables"][0]==7,"Conflict mutated input");
    return {{"kernel",OCC_VERSION_COMPLETE},{"protocol",1},{"fixtures",results},{"solver",{{"tangent",true},{"degreesOfFreedom",true},{"conflictPreservesInput",true}}}};
}
int main(int argc,char** argv) {
    try {
        if(argc==2&&std::string(argv[1])=="--self-test") { std::cout<<selfTest().dump()<<std::endl;return 0; }
        std::string line;char c;bool oversized=false;
        while(std::cin.get(c)) {
            if(c!='\n') { if(line.size()<2*1024*1024) line.push_back(c);else oversized=true;continue; }
            json id=nullptr;
            try {
                require(!oversized,"Request size limit exceeded");auto request=json::parse(line);id=request.value("id",json(nullptr));
                const auto op=request.at("operation").get<std::string>();json result;
                if(op=="ping") result={{"kernel",OCC_VERSION_COMPLETE},{"protocol",1},{"native",true}};
                else if(op=="evaluate") result=tessellate(shape(request.at("shape")),request.contains("tolerance")?positive(request["tolerance"]):.1);
                else if(op=="evaluate_solid"||op=="solid_info") { auto s=shape(request.at("shape"));result=solidInfo(s,request.value("singleSolid",false));if(op=="evaluate_solid") result.update(tessellate(s)); }
                else if(op=="interference") result=interference(request);
                else if(op=="inspect_edges") result={{"edges",edgeInfo(shape(request.at("shape")))}};
                else if(op=="solve") result=solve(request);
                else if(op=="self_test") result=selfTest();
                else throw std::invalid_argument("Unknown operation");
                std::cout<<json{{"id",id},{"result",result}}.dump()<<std::endl;
            } catch(const Standard_Failure& e) { std::cout<<json{{"id",id},{"error",{{"message",e.what()?e.what():"Geometry failed"}}}}.dump()<<std::endl; }
            catch(const std::exception& e) { std::cout<<json{{"id",id},{"error",{{"message",std::string(e.what()).substr(0,300)}}}}.dump()<<std::endl; }
            line.clear();oversized=false;
        }
    } catch(const std::exception& e) { std::cerr<<e.what()<<std::endl;return 1; }
}
