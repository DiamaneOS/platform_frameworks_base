/* Copyright (C) 2026 DiamaneOS */
#pragma once

#include "GateWritableBuffer.h"
#include "SkBitmap.h"
#include <hwui/Bitmap.h>
#include <cstdint>
#include <limits>

#ifdef __ANDROID__
#include <private/android/AHardwareBufferHelpers.h>
#include <ui/GraphicBuffer.h>
#include <ui/GraphicBufferMapper.h>
#endif

namespace android::diamaneos {

#ifdef __ANDROID__
inline bool writeBufferPixels(AHardwareBuffer* buffer, Bitmap& source, int left, int top, uint64_t reserved) {
    if (left < 0 || top < 0 || source.isHardware()) return false;
    const uint64_t actual = writableBufferBytes(buffer);
    if (actual == 0 || actual > reserved) return false;
    AHardwareBuffer_Desc descriptor = {};
    AHardwareBuffer_describe(buffer, &descriptor);
    SkBitmap pixels;
    source.getSkBitmap(&pixels);
    if (pixels.width() <= 0 || pixels.height() <= 0 || static_cast<uint64_t>(left) > descriptor.width ||
        static_cast<uint64_t>(top) > descriptor.height ||
        static_cast<uint64_t>(pixels.width()) > descriptor.width - static_cast<uint64_t>(left) ||
        static_cast<uint64_t>(pixels.height()) > descriptor.height - static_cast<uint64_t>(top)) return false;
    const uint64_t rowBytes = static_cast<uint64_t>(descriptor.stride) * 4;
    const uint64_t offset = static_cast<uint64_t>(top) * rowBytes + static_cast<uint64_t>(left) * 4;
    void* address = nullptr;
    if (AHardwareBuffer_lock(buffer, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, nullptr, &address) != 0) return false;
    const auto info = pixels.info().makeColorType(kRGBA_8888_SkColorType).makeAlphaType(kPremul_SkAlphaType);
    const bool copied = address != nullptr && pixels.readPixels(info,
            static_cast<uint8_t*>(address) + offset, static_cast<size_t>(rowBytes));
    const int unlocked = AHardwareBuffer_unlock(buffer, nullptr);
    return copied && unlocked == 0;
}
#endif

}  // namespace android::diamaneos
