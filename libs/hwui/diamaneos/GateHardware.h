/* Copyright (C) 2026 DiamaneOS */
#pragma once

#include "HardwareBitmapUploader.h"
#include "Properties.h"
#include "Gainmap.h"
#include "GateAllocation.h"
#ifdef __ANDROID__
#include <private/android/AHardwareBufferHelpers.h>
#include <ui/GraphicBuffer.h>
#include <ui/GraphicBufferMapper.h>
#endif

namespace android::diamaneos {

#ifdef __ANDROID__
inline uint64_t hardwarePlaneBytes(const SkImageInfo& info) {
    if (info.width() <= 0 || info.height() <= 0) return 0;
    const bool usingGL = uirenderer::Properties::getRenderPipelineType()
            == uirenderer::RenderPipelineType::SkiaGL;
    const auto format = uirenderer::HardwareBitmapUploader::determineFormat(info, usingGL);
    if (!format.valid) return 0;
    const AHardwareBuffer_Desc descriptor = {
            .width = static_cast<uint32_t>(info.width()),
            .height = static_cast<uint32_t>(info.height()),
            .layers = 1,
            .format = format.bufferFormat,
            .usage = AHARDWAREBUFFER_USAGE_CPU_READ_NEVER | AHARDWAREBUFFER_USAGE_CPU_WRITE_NEVER
                    | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE,
    };
    const uint64_t bytes = allocationSize(descriptor);
    // The uploader or the gain-map helper first converts an unsupported input to RGBA_8888.
    const uint64_t scratch = format.isSupported ? 0 : static_cast<uint64_t>(info.width()) * info.height() * 4;
    const uint64_t limit = static_cast<uint64_t>(std::numeric_limits<int64_t>::max());
    if (bytes == 0 || scratch > limit || bytes > limit - scratch) return 0;
    return bytes + scratch;
}
#endif

#ifdef __ANDROID__
inline uint64_t verifiedPlaneBytes(Bitmap& plane) {
    if (!plane.isHardware()) return 0;
    const auto* hardware = plane.hardwareBuffer();
    if (!hardware) return 0;
    const auto* graphic = AHardwareBuffer_to_GraphicBuffer(hardware);
    if (!graphic) return 0;
    uint64_t bytes = 0;
    const auto status = GraphicBufferMapper::get().getAllocationSize(graphic->handle, &bytes);
    return status == OK && bytes > 0 && bytes <= static_cast<uint64_t>(std::numeric_limits<int32_t>::max())
            && bytes == plane.getAllocationByteCount() ? bytes : 0;
}
#endif

inline uint64_t hardwareBytes(Bitmap& source) {
#ifdef __ANDROID__
    if (source.isHardware()) {
        uint64_t bytes = verifiedPlaneBytes(source);
        if (bytes == 0) return 0;
        if (source.hasGainmap()) {
            const auto gain = source.gainmap();
            if (!gain || !gain->bitmap) return 0;
            const uint64_t map = verifiedPlaneBytes(*gain->bitmap);
            if (map == 0 || bytes > static_cast<uint64_t>(std::numeric_limits<int64_t>::max()) - map) return 0;
            bytes += map;
        }
        return bytes;
    }
    uint64_t bytes = hardwarePlaneBytes(source.info());
    if (bytes == 0) return 0;
    if (source.hasGainmap()) {
        const auto gain = source.gainmap();
        if (!gain || !gain->bitmap || gain->bitmap->isHardware()) return 0;
        const uint64_t map = hardwarePlaneBytes(gain->bitmap->info());
        if (map == 0 || bytes > static_cast<uint64_t>(std::numeric_limits<int64_t>::max()) - map) return 0;
        bytes += map;
    }
    return bytes;
#else
    (void)source;
    return 0;
#endif
}

}  // namespace android::diamaneos
