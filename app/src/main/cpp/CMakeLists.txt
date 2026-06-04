cmake_minimum_required(VERSION 3.22.1)
project(copperhead_native)

# ITU G.722 codec by Steve Underwood (public domain), as vendored from
# Asterisk/spandsp. Bit-exact, ITU-test-vector-conformant.
add_library(g722jni SHARED
    g722_encode.c
    g722_decode.c
    g722_jni.c
)

# Asterisk's g722.c uses tgmath.h and saturating-arithmetic helpers; the
# clang/Android NDK toolchain handles these natively. No external libs needed
# beyond libm (for the math intrinsics) and Android's log lib (optional).
find_library(log-lib log)

target_link_libraries(g722jni
    ${log-lib}
)

# Silence the noisy "unused parameter" warnings from g722.c's auto-generated
# tables without losing real warnings.
target_compile_options(g722jni PRIVATE
    -Wall
    -Wno-unused-parameter
    -Wno-unused-variable
    -Wno-sign-compare
    -O2
)
