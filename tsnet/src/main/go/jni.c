#include <jni.h>
#include <stdlib.h>
#include <unistd.h>
#include "_cgo_export.h"

// Returns s as a Java string, or NULL if s is, and frees it. s is ASCII, so modified UTF-8 suffices.
static jstring javaString(JNIEnv *env, char *s) {
    if (s == NULL) return NULL;
    jstring result = (*env)->NewStringUTF(env, s);
    free(s);
    return result;
}

JNIEXPORT jlong JNICALL
Java_com_klethron_tsnet_TailnetNode_nativeStart(JNIEnv *env, jclass type, jobject callback, jbyteArray config) {
    jsize length = (*env)->GetArrayLength(env, config);
    jbyte *bytes = (*env)->GetByteArrayElements(env, config, NULL);
    if (bytes == NULL) return -1; // Pending allocation exception; Java never sees the result.
    state_store *store = state_new(env, callback);
    jlong result = store == NULL ? -10 : klethron_node_start(store, (char *) bytes, length);
    (*env)->ReleaseByteArrayElements(env, config, bytes, JNI_ABORT);
    return result;
}

JNIEXPORT void JNICALL
Java_com_klethron_tsnet_TailnetNode_nativeSetRoots(JNIEnv *env, jclass type, jbyteArray certificates) {
    jsize length = (*env)->GetArrayLength(env, certificates);
    jbyte *bytes = (*env)->GetByteArrayElements(env, certificates, NULL);
    if (bytes == NULL) return;
    int result = klethron_set_roots((char *) bytes, length);
    (*env)->ReleaseByteArrayElements(env, certificates, bytes, JNI_ABORT);
    if (result != 0) {
        jclass exception = (*env)->FindClass(env, "java/security/cert/CertificateException");
        if (exception != NULL) (*env)->ThrowNew(env, exception, "Invalid Android system roots");
    }
}

JNIEXPORT jstring JNICALL
Java_com_klethron_tsnet_TailnetNode_nativeAwaitStatus(JNIEnv *env, jclass type, jlong handle, jlong after, jlong timeout) {
    return javaString(env, klethron_node_await_status(handle, after, timeout));
}

JNIEXPORT void JNICALL
Java_com_klethron_tsnet_TailnetNode_nativeClose(JNIEnv *env, jclass type, jlong handle) {
    klethron_node_close(handle);
}

JNIEXPORT jstring JNICALL
Java_com_klethron_tsnet_TailnetNode_nativeTailscaleVersion(JNIEnv *env, jclass type) {
    return javaString(env, klethron_tailscale_version());
}

JNIEXPORT void JNICALL
Java_com_klethron_tsnet_TailnetNode_nativeSetDefaultNetwork(JNIEnv *env, jclass type, jbyteArray network) {
    // An interface name within IFNAMSIZ, NUL and an IPv4 address; preserve their standard UTF-8 bytes.
    char bytes[32];
    jsize length = (*env)->GetArrayLength(env, network);
    if (length >= (jsize) sizeof bytes) return;
    (*env)->GetByteArrayRegion(env, network, 0, length, (jbyte *) bytes);
    klethron_set_default_network(bytes, length);
}

JNIEXPORT jint JNICALL
Java_com_klethron_tsnet_TailnetNode_nativeDial(JNIEnv *env, jclass type, jlong handle, jbyteArray host, jint port, jint socket, jlong timeout) {
    // Java limits hosts to 255 UTF-8 bytes; copy exact bytes rather than modified UTF-8.
    char bytes[256];
    jsize length = (*env)->GetArrayLength(env, host);
    if (length >= (jsize) sizeof bytes) { // Invalid; Java checks first.
        close(socket);
        return -2;
    }
    (*env)->GetByteArrayRegion(env, host, 0, length, (jbyte *) bytes);
    return klethron_node_dial(handle, bytes, length, port, socket, timeout);
}

JNIEXPORT jint JNICALL
Java_com_klethron_tsnet_TailnetNode_nativeLogout(JNIEnv *env, jclass type, jlong handle, jlong timeout) {
    return klethron_node_logout(handle, timeout);
}

JNIEXPORT jint JNICALL
Java_com_klethron_tsnet_TailnetNode_nativeLogin(JNIEnv *env, jclass type, jlong handle) {
    return klethron_node_login(handle);
}
