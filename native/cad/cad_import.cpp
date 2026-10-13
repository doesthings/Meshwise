#include "cad_import.h"

#include <BRepBndLib.hxx>
#include <BRepLib_ToolTriangulatedShape.hxx>
#include <BRepMesh_IncrementalMesh.hxx>
#include <BRepTools.hxx>
#include <BRep_Builder.hxx>
#include <BRep_Tool.hxx>
#include <Bnd_Box.hxx>
#include <IFSelect_ReturnStatus.hxx>
#include <IGESCAFControl_Reader.hxx>
#include <IGESControl_Reader.hxx>
#include <Interface_Static.hxx>
#include <Message_ProgressIndicator.hxx>
#include <Message_ProgressScope.hxx>
#include <Poly_PolygonOnTriangulation.hxx>
#include <Poly_Triangulation.hxx>
#include <Quantity_ColorRGBA.hxx>
#include <STEPCAFControl_Reader.hxx>
#include <STEPControl_Reader.hxx>
#include <Standard_Failure.hxx>
#include <TDF_Label.hxx>
#include <TDataStd_Name.hxx>
#include <TDocStd_Document.hxx>
#include <TopExp.hxx>
#include <TopExp_Explorer.hxx>
#include <TopTools_IndexedMapOfShape.hxx>
#include <TopoDS.hxx>
#include <TopoDS_Compound.hxx>
#include <TopoDS_Edge.hxx>
#include <TopoDS_Face.hxx>
#include <XCAFApp_Application.hxx>
#include <XCAFDoc_ColorTool.hxx>
#include <XCAFDoc_DocumentTool.hxx>
#include <XCAFDoc_ShapeTool.hxx>
#include <XCAFPrs.hxx>
#include <XCAFPrs_DocumentExplorer.hxx>
#include <XCAFPrs_IndexedDataMapOfShapeStyle.hxx>
#include <XCAFPrs_Style.hxx>

#include <atomic>
#include <cmath>
#include <cstdio>
#include <stdexcept>
#include <thread>
#include <unordered_map>

#include "fsm_format.h"

namespace fsvcad {

namespace {

// Bridges OCCT progress reporting to our callback and supports cancellation.
// Parallel meshing advances the indicator from OCCT pool threads, and OCCT calls Show()
// on whichever thread advanced it. The callback goes through JNI, which is only valid on
// the thread that called into native code, so reports from other threads are dropped
// (the calling thread takes part in the work, so progress still gets through).
class ProgressBridge : public Message_ProgressIndicator {
public:
    ProgressBridge(const std::function<bool(float)>& fn, float lo, float hi)
        : fn_(fn), lo_(lo), hi_(hi), owner_(std::this_thread::get_id()) {}
    Standard_Boolean UserBreak() override { return cancelled_.load(std::memory_order_relaxed); }
    void Show(const Message_ProgressScope&, const Standard_Boolean) override {
        if (!fn_ || std::this_thread::get_id() != owner_) return;
        float v = lo_ + (hi_ - lo_) * (float)GetPosition();
        if (v - last_ < 0.004f) return;
        last_ = v;
        if (!fn_(v)) cancelled_ = true;
    }
    bool cancelled() const { return cancelled_; }

private:
    const std::function<bool(float)>& fn_;
    float lo_, hi_;
    float last_ = -1.f;
    std::thread::id owner_;
    std::atomic<bool> cancelled_{false};
};

std::string lower(std::string s) {
    for (char& c : s)
        if (c >= 'A' && c <= 'Z') c = (char)(c - 'A' + 'a');
    return s;
}

std::string labelName(const TDF_Label& label) {
    Handle(TDataStd_Name) name;
    if (!label.IsNull() && label.FindAttribute(TDataStd_Name::GetID(), name)) {
        TCollection_AsciiString s(name->Get(), '?');
        // Prefer UTF-8 when the name has non-ASCII characters.
        const TCollection_ExtendedString& ext = name->Get();
        if (!ext.IsAscii()) {
            int len = ext.LengthOfCString();
            std::string out((size_t)len + 1, '\0');
            Standard_PCharacter p = out.data();
            ext.ToUTF8CString(p);
            out.resize(std::strlen(out.c_str()));
            return out;
        }
        return std::string(s.ToCString());
    }
    return std::string();
}

uint32_t toRgba(const Quantity_ColorRGBA& c) {
    // Quantity colours are linear RGB; convert to sRGB for display.
    NCollection_Vec3<float> srgb = Quantity_Color::Convert_LinearRGB_To_sRGB(c.GetRGB().Rgb());
    auto b = [](float v) { return (uint32_t)std::lround(std::max(0.f, std::min(1.f, v)) * 255.f); };
    return b(srgb.r()) | (b(srgb.g()) << 8) | (b(srgb.b()) << 16) | (b(c.Alpha()) << 24);
}

struct PartMesh {
    std::string name;
    uint32_t color = 0;
    bool hasColor = false;
    std::vector<float> positions, normals;
    std::vector<uint32_t> colors;  // per vertex (only kept when faces differ)
    std::vector<uint32_t> indices, edges;
    bool mixedColors = false;
};

// Appends all faces of `shape` (transformed by `loc`) to `out`.
void appendShape(const TopoDS_Shape& shape, const TopLoc_Location& loc, uint32_t partColor,
                 const XCAFPrs_IndexedDataMapOfShapeStyle* styles, PartMesh& out) {
    const gp_Trsf nodeTrsf = loc.Transformation();
    const bool mirrored = nodeTrsf.IsNegative();
    TopTools_IndexedMapOfShape seenEdges;
    for (TopExp_Explorer ex(shape, TopAbs_FACE); ex.More(); ex.Next()) {
        const TopoDS_Face& face = TopoDS::Face(ex.Current());
        TopLoc_Location fl;
        Handle(Poly_Triangulation) tri = BRep_Tool::Triangulation(face, fl);
        if (tri.IsNull() || tri->NbTriangles() == 0) continue;
        if (!tri->HasNormals()) BRepLib_ToolTriangulatedShape::ComputeNormals(face, tri);
        const gp_Trsf tr = nodeTrsf * fl.Transformation();
        const bool reversed = (face.Orientation() == TopAbs_REVERSED) != mirrored;
        const bool flipNormals = face.Orientation() == TopAbs_REVERSED;

        uint32_t faceColor = partColor;
        if (styles) {
            const XCAFPrs_Style* st = styles->Seek(face);
            if (st && st->IsSetColorSurf()) faceColor = toRgba(st->GetColorSurfRGBA());
        }
        if (faceColor != partColor) out.mixedColors = true;

        const uint32_t base = (uint32_t)(out.positions.size() / 3);
        const int nn = tri->NbNodes();
        for (int i = 1; i <= nn; ++i) {
            gp_Pnt p = tri->Node(i).Transformed(tr);
            out.positions.insert(out.positions.end(), {(float)p.X(), (float)p.Y(), (float)p.Z()});
            gp_Dir n(0, 0, 1);
            if (tri->HasNormals()) {
                gp_Vec v = tri->Normal(i);
                if (v.SquareMagnitude() > 1e-20) n = gp_Dir(v).Transformed(tr);
            }
            if (flipNormals) n.Reverse();
            out.normals.insert(out.normals.end(), {(float)n.X(), (float)n.Y(), (float)n.Z()});
            out.colors.push_back(faceColor);
        }
        const int nt = tri->NbTriangles();
        for (int i = 1; i <= nt; ++i) {
            int a, b, c;
            tri->Triangle(i).Get(a, b, c);
            if (reversed) std::swap(b, c);
            out.indices.insert(out.indices.end(), {base + (uint32_t)(a - 1), base + (uint32_t)(b - 1), base + (uint32_t)(c - 1)});
        }
        // Boundary edges of the face become CAD edge lines (each shared edge once).
        for (TopExp_Explorer ee(face, TopAbs_EDGE); ee.More(); ee.Next()) {
            const TopoDS_Edge& edge = TopoDS::Edge(ee.Current());
            if (seenEdges.Contains(edge)) continue;
            seenEdges.Add(edge);
            Handle(Poly_PolygonOnTriangulation) poly = BRep_Tool::PolygonOnTriangulation(edge, tri, fl);
            if (poly.IsNull()) continue;
            const TColStd_Array1OfInteger& nodes = poly->Nodes();
            for (int k = nodes.Lower(); k < nodes.Upper(); ++k) {
                int na = nodes(k), nb = nodes(k + 1);
                if (na < 1 || nb < 1 || na > nn || nb > nn) continue;
                out.edges.push_back(base + (uint32_t)(na - 1));
                out.edges.push_back(base + (uint32_t)(nb - 1));
            }
        }
    }
}

void writeFile(const std::string& path, const std::vector<uint8_t>& bytes) {
    FILE* f = std::fopen(path.c_str(), "wb");
    if (!f) throw std::runtime_error("Could not write the converted mesh");
    size_t n = std::fwrite(bytes.data(), 1, bytes.size(), f);
    std::fclose(f);
    if (n != bytes.size()) throw std::runtime_error("Could not write the converted mesh (disk full?)");
}

CadResult importImpl(const std::string& inputPath, const std::string& extIn, const std::string& outputPath,
                     const CadOptions& options, const std::function<bool(float)>& progress) {
    CadResult result;
    const std::string ext = lower(extIn);
    auto report = [&](float v) {
        if (progress && !progress(v)) throw std::runtime_error("cancelled");
    };
    report(0.01f);

    Handle(XCAFApp_Application) app = XCAFApp_Application::GetApplication();
    Handle(TDocStd_Document) doc;
    app->NewDocument("MDTV-XCAF", doc);
    bool haveDoc = false;
    TopoDS_Shape plainShape;

    if (ext == "step" || ext == "stp") {
        STEPCAFControl_Reader reader;
        reader.SetColorMode(true);
        reader.SetNameMode(true);
        reader.SetLayerMode(false);
        reader.SetPropsMode(false);
        reader.SetGDTMode(false);
        reader.SetMatMode(false);
        reader.SetViewMode(false);
        if (reader.ReadFile(inputPath.c_str()) != IFSelect_RetDone)
            throw std::runtime_error("This STEP file could not be read");
        report(0.25f);
        Handle(ProgressBridge) bridge = new ProgressBridge(progress, 0.25f, 0.55f);
        if (!reader.Transfer(doc, bridge->Start())) {
            if (bridge->cancelled()) throw std::runtime_error("cancelled");
            throw std::runtime_error("The STEP file contains no usable geometry");
        }
        haveDoc = true;
    } else if (ext == "iges" || ext == "igs") {
        IGESCAFControl_Reader reader;
        reader.SetColorMode(true);
        reader.SetNameMode(true);
        reader.SetLayerMode(false);
        if (reader.ReadFile(inputPath.c_str()) != IFSelect_RetDone)
            throw std::runtime_error("This IGES file could not be read");
        report(0.25f);
        Handle(ProgressBridge) bridge = new ProgressBridge(progress, 0.25f, 0.55f);
        if (!reader.Transfer(doc, bridge->Start())) {
            if (bridge->cancelled()) throw std::runtime_error("cancelled");
            throw std::runtime_error("The IGES file contains no usable geometry");
        }
        haveDoc = true;
    } else if (ext == "brep" || ext == "brp") {
        BRep_Builder builder;
        Handle(ProgressBridge) bridge = new ProgressBridge(progress, 0.02f, 0.55f);
        if (!BRepTools::Read(plainShape, inputPath.c_str(), builder, bridge->Start()) || plainShape.IsNull())
            throw std::runtime_error("This BREP file could not be read");
        Handle(XCAFDoc_ShapeTool) st = XCAFDoc_DocumentTool::ShapeTool(doc->Main());
        st->AddShape(plainShape, false);
        haveDoc = true;
    } else {
        throw std::runtime_error("Unsupported CAD format: " + extIn);
    }
    report(0.55f);

    Handle(XCAFDoc_ShapeTool) shapeTool = XCAFDoc_DocumentTool::ShapeTool(doc->Main());
    TDF_LabelSequence roots;
    shapeTool->GetFreeShapes(roots);
    if (roots.IsEmpty()) throw std::runtime_error("The file contains no shapes");

    // Tessellate everything once (instances share their tessellation).
    TopoDS_Compound all;
    BRep_Builder bb;
    bb.MakeCompound(all);
    for (TDF_LabelSequence::Iterator it(roots); it.More(); it.Next()) {
        TopoDS_Shape s = XCAFDoc_ShapeTool::GetShape(it.Value());
        if (!s.IsNull()) bb.Add(all, s);
    }
    Bnd_Box box;
    BRepBndLib::Add(all, box);
    if (box.IsVoid()) throw std::runtime_error("The file contains no geometry");
    double diag = std::sqrt(box.SquareExtent());
    if (!(diag > 0)) diag = 1.0;
    IMeshTools_Parameters params;
    params.Deflection = std::max(1e-6, diag * options.relativeDeflection);
    params.Angle = options.angularDeflection;
    params.InParallel = true;
    params.Relative = false;
    params.MinSize = params.Deflection * 0.1;
    {
        Handle(ProgressBridge) bridge = new ProgressBridge(progress, 0.55f, 0.88f);
        BRepMesh_IncrementalMesh mesher(all, params, bridge->Start());
        if (bridge->cancelled()) throw std::runtime_error("cancelled");
    }
    report(0.88f);

    // Walk leaf nodes with resolved styles and locations.
    std::vector<PartMesh> parts;
    XCAFPrs_Style defStyle;
    XCAFPrs_DocumentExplorer explorer(doc, XCAFPrs_DocumentExplorerFlags_OnlyLeafNodes, defStyle);
    std::unordered_map<std::string, int> nameCount;
    for (; explorer.More(); explorer.Next()) {
        const XCAFPrs_DocumentNode& node = explorer.Current();
        TopoDS_Shape shape = XCAFDoc_ShapeTool::GetShape(node.RefLabel);
        if (shape.IsNull()) continue;
        PartMesh pm;
        // Instance name, falling back to the product name.
        pm.name = labelName(node.Label);
        if (pm.name.empty() || pm.name == "=>[0:1:1:1]") pm.name = labelName(node.RefLabel);
        if (pm.name.empty()) pm.name = "Part";
        if (node.Style.IsSetColorSurf()) {
            pm.color = toRgba(node.Style.GetColorSurfRGBA());
            pm.hasColor = true;
        }
        // Per-face colours defined on the referenced shape.
        XCAFPrs_IndexedDataMapOfShapeStyle styles;
        XCAFPrs::CollectStyleSettings(node.RefLabel, TopLoc_Location(), styles);
        // Faces inherit the instance colour unless they override it.
        uint32_t base = pm.hasColor ? pm.color : 0u;
        appendShape(shape, node.Location, base, styles.IsEmpty() ? nullptr : &styles, pm);
        if (pm.indices.empty()) continue;
        if (!pm.hasColor && pm.mixedColors) {
            // No instance colour but coloured faces: keep vertex colours, faces without a
            // colour use a neutral grey.
            for (uint32_t& c : pm.colors) if (c == 0) c = 0xFFC8C8C8u;
        }
        int& count = nameCount[pm.name];
        if (++count > 1) pm.name += " (" + std::to_string(count) + ")";
        result.triangles += (long long)(pm.indices.size() / 3);
        parts.push_back(std::move(pm));
        if (parts.size() % 16 == 0) report(0.88f + 0.08f * std::min(1.f, (float)parts.size() / 2000.f));
    }
    if (parts.empty()) throw std::runtime_error("The CAD model could not be triangulated");
    if (parts.size() > 65535) throw std::runtime_error("Too many parts (limit is 65535)");

    fsv::FsmWriter writer((uint32_t)parts.size());
    for (const PartMesh& pm : parts) {
        uint32_t flags = (pm.hasColor ? fsv::kFsmFlagFileColor : 0) | (pm.mixedColors ? fsv::kFsmFlagVertexColors : 0);
        writer.part(pm.name, pm.hasColor ? (pm.color | 0xFF000000u) : 0u, flags, pm.positions.data(), pm.normals.data(),
                    pm.mixedColors ? pm.colors.data() : nullptr, (uint32_t)(pm.positions.size() / 3), pm.indices.data(),
                    (uint32_t)pm.indices.size(), pm.edges.data(), (uint32_t)pm.edges.size());
    }
    writeFile(outputPath, writer.bytes());
    result.parts = (int)parts.size();
    (void)haveDoc;
    app->Close(doc);
    report(1.f);
    return result;
}

}  // namespace

CadResult importCad(const std::string& inputPath, const std::string& ext, const std::string& outputPath,
                    const CadOptions& options, const std::function<bool(float)>& progress) {
    try {
        return importImpl(inputPath, ext, outputPath, options, progress);
    } catch (const Standard_Failure& f) {
        throw std::runtime_error(std::string("CAD kernel error: ") + (f.GetMessageString() ? f.GetMessageString() : "unknown"));
    }
}

}  // namespace fsvcad
