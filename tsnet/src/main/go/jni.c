#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include "_cgo_export.h"

JNIEXPORT jlong JNICALL
Java_com_klethron_tsnet_TsnetNode_nativeCreate(JNIEnv *env, jclass type) {
    return klethron_node_create();
}

JNIEXPORT jint JNICALL
Java_com_klethron_tsnet_TsnetNode_nativeStart(JNIEnv *env, jclass type, jlong handle, jbyteArray config) {
    // Java encodes standard UTF-8, including supplementary characters in paths.
    jsize length = (*env)->GetArrayLength(env, config);
    jbyte *bytes = (*env)->GetByteArrayElements(env, config, NULL);
    if (bytes == NULL) return -1; // Pending allocation exception; Java never sees the result.
    jint result = klethron_node_start(handle, (char *) bytes, length);
    (*env)->ReleaseByteArrayElements(env, config, bytes, JNI_ABORT);
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_klethron_tsnet_TsnetNode_nativeAwaitStatus(JNIEnv *env, jclass type, jlong handle, jlong after, jlong timeoutMillis) {
    char *record = klethron_node_await_status(handle, after, timeoutMillis);
    if (record == NULL) return NULL; // Closed.
    // Records are ASCII, so JNI modified UTF-8 is sufficient.
    jstring result = (*env)->NewStringUTF(env, record);
    free(record);
    return result;
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

JNIEXPORT jint JNICALL
Java_com_klethron_tsnet_TsnetNode_nativeDial(JNIEnv *env, jclass type, jlong handle, jbyteArray host, jint port, jlong timeoutMillis) {
    // Java limits hosts to 255 UTF-8 bytes; copy exact bytes rather than modified UTF-8.
    char bytes[256];
    jsize length = (*env)->GetArrayLength(env, host);
    if (length >= (jsize) sizeof bytes) return -3; // Invalid; Java checks first.
    (*env)->GetByteArrayRegion(env, host, 0, length, (jbyte *) bytes);
    return klethron_node_dial(handle, bytes, length, port, timeoutMillis);
}
