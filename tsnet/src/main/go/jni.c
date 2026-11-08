#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include "_cgo_export.h"

JNIEXPORT jlong JNICALL
Java_com_klethron_tsnet_TsnetNode_nativeCreate(JNIEnv *env, jclass type) {
    return klethron_node_create();
}

JNIEXPORT void JNICALL
Java_com_klethron_tsnet_TsnetNode_nativeClose(JNIEnv *env, jclass type, jlong handle) {
    klethron_node_close(handle);
}

JNIEXPORT jstring JNICALL
Java_com_klethron_tsnet_TsnetNode_nativeTailscaleVersion(JNIEnv *env, jclass type) {
    // Module versions are ASCII, so JNI modified UTF-8 is sufficient here.
    char *version = klethron_tailscale_version();
    jstring result = (*env)->NewStringUTF(env, version);
    free(version);
    return result;
}

JNIEXPORT void JNICALL
Java_com_klethron_tsnet_TsnetNode_nativeSetDefaultNetwork(JNIEnv *env, jclass type, jbyteArray name, jint gateway) {
    // Java limits names to IFNAMSIZ - 1 bytes; copy exact bytes rather than modified UTF-8.
    char bytes[16];
    jsize length = (*env)->GetArrayLength(env, name);
    if (length >= (jsize) sizeof bytes) return;
    (*env)->GetByteArrayRegion(env, name, 0, length, (jbyte *) bytes);
    klethron_set_default_network(bytes, length, (uint32_t) gateway);
}
