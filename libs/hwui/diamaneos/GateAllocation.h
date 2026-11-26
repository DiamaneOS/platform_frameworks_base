/* Copyright (C) 2026 DiamaneOS */
#pragma once

#ifdef __ANDROID__
#include <android/hardware/graphics/mapper/4.0/IMapper.h>
#include <android/hardware_buffer.h>
#include <gralloctypes/Gralloc4.h>
#include <cstdint>
#include <limits>

namespace android::diamaneos {

// Queries the allocator descriptor before any image buffer is allocated.
inline uint64_t allocationSize(const AHardwareBuffer_Desc& source) {
    using hardware::graphics::mapper::V4_0::Error;
    using hardware::graphics::mapper::V4_0::IMapper;
    const auto mapper = IMapper::tryGetService();
    if (!mapper || mapper->isRemote()) return 0;
    IMapper::BufferDescriptorInfo descriptor{};
    descriptor.width = source.width;
    descriptor.height = source.height;
    descriptor.layerCount = source.layers;
    descriptor.format = static_cast<hardware::graphics::common::V1_2::PixelFormat>(source.format);
    descriptor.usage = source.usage;
    uint64_t bytes = 0;
    const auto result = mapper->getFromBufferDescriptorInfo(descriptor, gralloc4::MetadataType_AllocationSize,
            [&bytes](Error error, const hardware::hidl_vec<uint8_t>& metadata) {
                uint64_t value = 0;
                if (error == Error::NONE && gralloc4::decodeAllocationSize(metadata, &value) == 0
                        && value <= static_cast<uint64_t>(std::numeric_limits<int64_t>::max())) {
                    bytes = value;
                }
            });
    return result.isOk() ? bytes : 0;
}

}  // namespace android::diamaneos
#endif
