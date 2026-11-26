/*
 * Copyright (C) 2026 DiamaneOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef AAPT2_CMD_DEVICE_IMAGES_H
#define AAPT2_CMD_DEVICE_IMAGES_H

#include <string_view>

namespace aapt {

// DiamaneOS: the aapt2 built for the device (the WebApp APEX, whose installer compiles the icons a website names)
// parses no image. A PNG is stored as it is, as with --no-crunch, and its bytes are read later only by processes
// that decode through the decode gate. A nine-patch, which only a parse can compile, is refused. The host build
// tool crunches as before.
#if defined(__ANDROID__)
inline constexpr bool kCompileParsesImages = false;
#else
inline constexpr bool kCompileParsesImages = true;
#endif

enum class ImageCompile {
  kParse,   // CompilePng: libpng reads the image
  kStore,   // CompileFile: the bytes as they are
  kRefuse,  // an error
};

// How compile takes a file of a resource type other than values, xml and raw, with extension `extension`.
inline ImageCompile ChooseImageCompile(std::string_view extension, bool no_png_crunch, bool parses_images) {
  const bool nine_patch = extension == "9.png";
  if (!nine_patch && (no_png_crunch || extension != "png")) {
    return ImageCompile::kStore;
  }
  if (parses_images) {
    return ImageCompile::kParse;
  }
  return nine_patch ? ImageCompile::kRefuse : ImageCompile::kStore;
}

}  // namespace aapt

#endif  // AAPT2_CMD_DEVICE_IMAGES_H
