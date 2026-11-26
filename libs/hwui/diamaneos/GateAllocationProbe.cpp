/* Copyright (C) 2026 DiamaneOS */
#include "GateAllocation.h"
#include <cstdio>
#include <array>
#include <private/android/AHardwareBufferHelpers.h>
#include <ui/GraphicBuffer.h>
#include <ui/GraphicBufferMapper.h>

int main() {
    struct Shape { uint32_t width, height; };
    constexpr std::array<Shape, 4> shapes = {{{1, 1}, {17, 13}, {300, 217}, {1024, 769}}};
    constexpr std::array<uint32_t, 3> formats = {{AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM,
            AHARDWAREBUFFER_FORMAT_R16G16B16A16_FLOAT, AHARDWAREBUFFER_FORMAT_R8_UNORM}};
    int checked = 0;
    int failed = 0;
    for (uint32_t format : formats) {
        for (const auto shape : shapes) {
            const AHardwareBuffer_Desc descriptor = {
                    .width = shape.width,
                    .height = shape.height,
                    .layers = 1,
                    .format = format,
                    .usage = AHARDWAREBUFFER_USAGE_CPU_READ_NEVER | AHARDWAREBUFFER_USAGE_CPU_WRITE_NEVER
                            | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE,
            };
            const uint64_t predicted = android::diamaneos::allocationSize(descriptor);
            if (!AHardwareBuffer_isSupported(&descriptor)) {
                std::printf("UNSUPPORTED format=%u %ux%u query=%llu\n", format, shape.width, shape.height,
                        static_cast<unsigned long long>(predicted));
                continue;
            }
            AHardwareBuffer* buffer = nullptr;
            const int status = AHardwareBuffer_allocate(&descriptor, &buffer);
            if (status != 0 || buffer == nullptr) {
                std::printf("FAIL allocation status=%d\n", status);
                failed++;
                continue;
            }
            uint64_t actual = 0;
            const auto* graphic = android::AHardwareBuffer_to_GraphicBuffer(buffer);
            const auto sizeStatus = android::GraphicBufferMapper::get().getAllocationSize(graphic->handle, &actual);
            AHardwareBuffer_release(buffer);
            checked++;
            const bool matched = sizeStatus == android::OK && actual > 0 && predicted != 0 && predicted >= actual;
            std::printf("%s format=%u %ux%u predicted=%llu actual=%llu\n", matched ? "PASS" : "FAIL",
                    format, shape.width, shape.height, static_cast<unsigned long long>(predicted),
                    static_cast<unsigned long long>(actual));
            if (!matched) failed++;
        }
    }
    std::printf("ALLOCATION-PROBE checked=%d failed=%d\n", checked, failed);
    return checked >= 4 && failed == 0 ? 0 : 1;
}
