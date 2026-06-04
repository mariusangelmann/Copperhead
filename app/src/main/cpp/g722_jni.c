/*
 * JNI bridge to spandsp/Asterisk G.722 codec (Steve Underwood, public domain).
 *
 * One C g722_encode_state_t / g722_decode_state_t is allocated per Kotlin
 * G722Codec instance and stored as an opaque jlong handle. Allocation is
 * malloc/free — small and fixed-size, no GC pressure.
 *
 * We use 64 kbps mode (rate=64000) and the 16 kHz output sample mode
 * (options=0). The codec internally handles QMF + low/high sub-band ADPCM.
 *
 * No JNI_OnLoad — System.loadLibrary("g722jni") in the Kotlin object init
 * triggers the dynamic linker.
 */
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include "g722.h"

#define LOG_TAG "g722jni"

JNIEXPORT jlong JNICALL
Java_com_copperhead_gateway_sip_G722Codec_nativeNewEncoder(JNIEnv *env, jclass clazz) {
    g722_encode_state_t *state = (g722_encode_state_t *) malloc(sizeof(g722_encode_state_t));
    if (state == NULL) return 0;
    // rate=64000 → 64 kbps Mode 1, options=0 → standard 16 kHz wideband
    if (g722_encode_init(state, 64000, 0) == NULL) {
        free(state);
        return 0;
    }
    return (jlong) (intptr_t) state;
}

JNIEXPORT jlong JNICALL
Java_com_copperhead_gateway_sip_G722Codec_nativeNewDecoder(JNIEnv *env, jclass clazz) {
    g722_decode_state_t *state = (g722_decode_state_t *) malloc(sizeof(g722_decode_state_t));
    if (state == NULL) return 0;
    if (g722_decode_init(state, 64000, 0) == NULL) {
        free(state);
        return 0;
    }
    return (jlong) (intptr_t) state;
}

JNIEXPORT void JNICALL
Java_com_copperhead_gateway_sip_G722Codec_nativeFreeEncoder(JNIEnv *env, jclass clazz, jlong handle) {
    if (handle != 0) {
        g722_encode_state_t *state = (g722_encode_state_t *) (intptr_t) handle;
        // NB: g722_encode_release() in Asterisk/spandsp ALREADY calls free()
        // internally. Calling free(state) again here is a double-free that
        // Scudo (Android's allocator) catches with SIGABRT — that's exactly
        // the crash we hit at call-end. So: do NOT free() again here.
        g722_encode_release(state);
    }
}

JNIEXPORT void JNICALL
Java_com_copperhead_gateway_sip_G722Codec_nativeFreeDecoder(JNIEnv *env, jclass clazz, jlong handle) {
    if (handle != 0) {
        g722_decode_state_t *state = (g722_decode_state_t *) (intptr_t) handle;
        // Same convention as the encoder: g722_decode_release() does the free().
        g722_decode_release(state);
    }
}

/*
 * Encode pcmCount 16-bit PCM samples (16 kHz) → G.722 octets.
 * Returns the number of octets written (= pcmCount / 2).
 */
JNIEXPORT jint JNICALL
Java_com_copperhead_gateway_sip_G722Codec_nativeEncode(
        JNIEnv *env, jclass clazz,
        jlong handle,
        jshortArray pcmArr, jint pcmOffset, jint pcmCount,
        jbyteArray outArr, jint outOffset) {
    if (handle == 0) return -1;
    g722_encode_state_t *state = (g722_encode_state_t *) (intptr_t) handle;

    jshort *pcm = (*env)->GetShortArrayElements(env, pcmArr, NULL);
    jbyte *out = (*env)->GetByteArrayElements(env, outArr, NULL);
    if (pcm == NULL || out == NULL) {
        if (pcm) (*env)->ReleaseShortArrayElements(env, pcmArr, pcm, JNI_ABORT);
        if (out) (*env)->ReleaseByteArrayElements(env, outArr, out, JNI_ABORT);
        return -1;
    }

    int wrote = g722_encode(state,
                            (uint8_t *) (out + outOffset),
                            (int16_t *) (pcm + pcmOffset),
                            pcmCount);

    (*env)->ReleaseShortArrayElements(env, pcmArr, pcm, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, outArr, out, 0);
    return wrote;
}

/*
 * Decode g722Count octets → 2 × g722Count 16-bit PCM samples (16 kHz).
 * Returns the number of PCM samples written.
 */
JNIEXPORT jint JNICALL
Java_com_copperhead_gateway_sip_G722Codec_nativeDecode(
        JNIEnv *env, jclass clazz,
        jlong handle,
        jbyteArray g722Arr, jint g722Offset, jint g722Count,
        jshortArray outArr, jint outOffset) {
    if (handle == 0) return -1;
    g722_decode_state_t *state = (g722_decode_state_t *) (intptr_t) handle;

    jbyte *g722 = (*env)->GetByteArrayElements(env, g722Arr, NULL);
    jshort *out = (*env)->GetShortArrayElements(env, outArr, NULL);
    if (g722 == NULL || out == NULL) {
        if (g722) (*env)->ReleaseByteArrayElements(env, g722Arr, g722, JNI_ABORT);
        if (out) (*env)->ReleaseShortArrayElements(env, outArr, out, JNI_ABORT);
        return -1;
    }

    int wrote = g722_decode(state,
                            (int16_t *) (out + outOffset),
                            (uint8_t *) (g722 + g722Offset),
                            g722Count);

    (*env)->ReleaseByteArrayElements(env, g722Arr, g722, JNI_ABORT);
    (*env)->ReleaseShortArrayElements(env, outArr, out, 0);
    return wrote;
}
