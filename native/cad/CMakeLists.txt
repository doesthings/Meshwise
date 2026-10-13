# libmeshwise_cad.so: STEP/IGES/BREP import, linked statically against OCCT.
set(OCCT_LIB_DIR "${FSV_OCCT_ROOT}/lib")
set(OCCT_INCLUDE_DIR "${FSV_OCCT_ROOT}/include/opencascade")

# Order does not matter inside the link group.
set(OCCT_TOOLKITS
  TKDESTEP TKDEIGES TKDE TKXCAF TKBinXCAF TKBin TKBinL TKVCAF TKCAF TKLCAF TKCDF TKV3d TKService TKHLR
  TKXSBase TKMesh TKShHealing TKBool TKBO TKPrim TKTopAlgo TKGeomAlgo TKBRep TKGeomBase TKG3d TKG2d
  TKMath TKernel)

set(OCCT_LINK_LIBS)
foreach(tk ${OCCT_TOOLKITS})
  if(EXISTS "${OCCT_LIB_DIR}/lib${tk}.a")
    list(APPEND OCCT_LINK_LIBS "${OCCT_LIB_DIR}/lib${tk}.a")
  endif()
endforeach()

add_library(meshwise_cad SHARED cad_import.cpp jni_cad.cpp)
target_include_directories(meshwise_cad PRIVATE ${OCCT_INCLUDE_DIR} ${CMAKE_CURRENT_SOURCE_DIR}/../core ${CMAKE_CURRENT_SOURCE_DIR}/../jni)
target_compile_options(meshwise_cad PRIVATE -fexceptions -frtti -O2 -Wno-deprecated-declarations)
set_target_properties(meshwise_cad PROPERTIES CXX_VISIBILITY_PRESET hidden)
target_link_libraries(meshwise_cad PRIVATE -Wl,--start-group ${OCCT_LINK_LIBS} -Wl,--end-group log)
target_link_options(meshwise_cad PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,--icf=safe -Wl,-O2)
