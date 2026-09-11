cmake_minimum_required(VERSION 3.22)
project(paw-engine CXX)

find_package(oboe REQUIRED CONFIG)

add_library(paw-engine SHARED
        jni_bridge.cpp
        AudioEngine.cpp
        EffectHost.cpp
        Mixdown.cpp
        Peaks.cpp
        RtCheck.cpp
        Timeline.cpp
        WavFile.cpp
        effects/BuiltinEffects.cpp)

target_compile_features(paw-engine PRIVATE cxx_std_17)
# -O2 unconditionally: AGP's debug variant configures CMake with empty debug
# flags, which leaves the whole engine (audio callback included) at -O0, and
# real-time audio cannot afford that.
target_compile_options(paw-engine PRIVATE -Wall -Wextra -O2)
target_link_libraries(paw-engine PRIVATE oboe::oboe android log dl)
