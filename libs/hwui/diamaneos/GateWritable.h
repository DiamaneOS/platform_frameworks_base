/* Copyright (C) 2026 DiamaneOS */
#pragma once

#include "GateWritableBuffer.h"
#include "SkBitmap.h"
#include "SkCanvas.h"
#include "SkColorSpace.h"
#include "SkImage.h"
#include "SkPaint.h"
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
            static_cast<uint8_t*>(address) + offset, static_cast<size_t>(rowBytes), 0, 0);
    const int unlocked = AHardwareBuffer_unlock(buffer, nullptr);
    return copied && unlocked == 0;
}
#endif

// BitmapFactory's legacy density transform, accepting only immutable checked straight pixels and private output.
inline bool writeStraightPixels(Bitmap& source, Bitmap& destination) {
    if (source.isHardware() || destination.isHardware() || !source.isImmutable() || destination.isImmutable()) return false;
    SkBitmap input, output;
    source.getSkBitmap(&input);
    destination.getSkBitmap(&output);
    if (input.width() < 1 || input.height() < 1 || output.width() < 1 || output.height() < 1 ||
            (input.alphaType() != kUnpremul_SkAlphaType && input.alphaType() != kOpaque_SkAlphaType) ||
            (output.alphaType() != kUnpremul_SkAlphaType && output.alphaType() != kOpaque_SkAlphaType) ||
            input.getPixels() == nullptr || output.getPixels() == nullptr) return false;
    if (input.width() == output.width() && input.height() == output.height()) {
        return input.readPixels(output.info(), output.getPixels(), output.rowBytes(), 0, 0);
    }
    if (input.colorType() != output.colorType() ||
            !SkColorSpace::Equals(input.colorSpace(), output.colorSpace())) return false;
    const auto image = input.asImage();
    if (!image) return false;
    SkPaint paint;
    paint.setBlendMode(SkBlendMode::kSrc);
    SkCanvas canvas(output, SkCanvas::ColorBehavior::kLegacy);
    canvas.scale(output.width() / float(input.width()), output.height() / float(input.height()));
    canvas.drawImage(image, 0.0f, 0.0f, SkSamplingOptions(SkFilterMode::kLinear), &paint);
    return true;
}

}  // namespace android::diamaneos
