/* Copyright (C) 2026 DiamaneOS */
#pragma once
#include "GateAllocation.h"
#include <cstdint>
#include <limits>
#ifdef __ANDROID__
#include <private/android/AHardwareBufferHelpers.h>
#include <ui/GraphicBuffer.h>
#include <ui/GraphicBufferMapper.h>
namespace android::diamaneos {
inline AHardwareBuffer_Desc writableDescriptor(uint32_t width, uint32_t height) {
    return {
            .width = width,
            .height = height,
            .layers = 1,
            .format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM,
            .usage = AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE,
    };
}

inline uint64_t writableAllocationBytes(int width, int height) {
    if (width <= 0 || height <= 0) return 0;
    const auto descriptor = writableDescriptor(static_cast<uint32_t>(width), static_cast<uint32_t>(height));
    const uint64_t bytes = allocationSize(descriptor);
    return bytes <= static_cast<uint64_t>(std::numeric_limits<int32_t>::max()) ? bytes : 0;
}

inline uint64_t writableBufferBytes(AHardwareBuffer* buffer) {
    if (buffer == nullptr) return 0;
    AHardwareBuffer_Desc descriptor = {};
    AHardwareBuffer_describe(buffer, &descriptor);
    const auto expected = writableDescriptor(descriptor.width, descriptor.height);
    if (descriptor.width == 0 || descriptor.height == 0 || descriptor.layers != 1 ||
        descriptor.format != expected.format || descriptor.usage != expected.usage ||
        descriptor.stride < descriptor.width) return 0;
    const auto* graphic = AHardwareBuffer_to_GraphicBuffer(buffer);
    if (graphic == nullptr) return 0;
    uint64_t actual = 0;
    const auto status = GraphicBufferMapper::get().getAllocationSize(graphic->handle, &actual);
    if (status != OK || actual == 0 || actual > static_cast<uint64_t>(std::numeric_limits<int32_t>::max())) return 0;
    const uint64_t rowBytes = static_cast<uint64_t>(descriptor.stride) * 4;
    return rowBytes <= actual / descriptor.height ? actual : 0;
}

}  // namespace android::diamaneos
#endif
