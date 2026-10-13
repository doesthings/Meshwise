#pragma once

// Headless OpenGL ES 3 context (Mesa surfaceless / pbuffer) for desktop renders and tests.
namespace fsvhost {

bool createHeadlessContext(int width, int height);
void destroyHeadlessContext();

}  // namespace fsvhost
