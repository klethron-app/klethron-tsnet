#include <jni.h>
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
