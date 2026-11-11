#include <jni.h>
#include <stdlib.h>
#include "_cgo_export.h"

JNIEXPORT jlong JNICALL
Java_com_klethron_tsnet_TailnetNode_nativeStart(JNIEnv *env, jclass type, jbyteArray config) {
    // Java encodes standard UTF-8, including supplementary characters in paths.
    jsize length = (*env)->GetArrayLength(env, config);
    jbyte *bytes = (*env)->GetByteArrayElements(env, config, NULL);
    if (bytes == NULL) return -1; // Pending allocation exception; Java never sees the result.
    jlong result = klethron_node_start((char *) bytes, length);
    (*env)->ReleaseByteArrayElements(env, config, bytes, JNI_ABORT);
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_klethron_tsnet_TailnetNode_nativeAwaitStatus(JNIEnv *env, jclass type, jlong handle, jlong after, jlong timeout) {
    char *record = klethron_node_await_status(handle, after, timeout);
    if (record == NULL) return NULL; // Closed.
    // Records are ASCII, so JNI modified UTF-8 is sufficient.
    jstring result = (*env)->NewStringUTF(env, record);
    free(record);
    return result;
}

JNIEXPORT void JNICALL
Java_com_klethron_tsnet_TailnetNode_nativeClose(JNIEnv *env, jclass type, jlong handle) {
    klethron_node_close(handle);
}

JNIEXPORT jstring JNICALL
Java_com_klethron_tsnet_TailnetNode_nativeTailscaleVersion(JNIEnv *env, jclass type) {
    // Module versions are ASCII, so JNI modified UTF-8 is sufficient here.
    char *version = klethron_tailscale_version();
    jstring result = (*env)->NewStringUTF(env, version);
    free(version);
    return result;
}

JNIEXPORT void JNICALL
Java_com_klethron_tsnet_TailnetNode_nativeSetDefaultNetwork(JNIEnv *env, jclass type, jbyteArray name, jint gateway) {
    // Android interface names fit IFNAMSIZ; preserve their standard UTF-8 bytes.
    char bytes[16];
    jsize length = (*env)->GetArrayLength(env, name);
    if (length >= (jsize) sizeof bytes) return;
    (*env)->GetByteArrayRegion(env, name, 0, length, (jbyte *) bytes);
    klethron_set_default_network(bytes, length, gateway);
}

JNIEXPORT jint JNICALL
Java_com_klethron_tsnet_TailnetNode_nativeDial(JNIEnv *env, jclass type, jlong handle, jbyteArray host, jint port, jlong timeout) {
    // Java limits hosts to 255 UTF-8 bytes; copy exact bytes rather than modified UTF-8.
    char bytes[256];
    jsize length = (*env)->GetArrayLength(env, host);
    if (length >= (jsize) sizeof bytes) return -2; // Invalid; Java checks first.
    (*env)->GetByteArrayRegion(env, host, 0, length, (jbyte *) bytes);
    return klethron_node_dial(handle, bytes, length, port, timeout);
}

JNIEXPORT jint JNICALL
Java_com_klethron_tsnet_TailnetNode_nativeLogout(JNIEnv *env, jclass type, jlong handle, jlong timeout) {
    return klethron_node_logout(handle, timeout);
}

JNIEXPORT jint JNICALL
Java_com_klethron_tsnet_TailnetNode_nativeLogin(JNIEnv *env, jclass type, jlong handle) {
    return klethron_node_login(handle);
}
