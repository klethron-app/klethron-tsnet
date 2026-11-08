//go:build klethron_test

#include <jni.h>
#include <stdlib.h>
#include "_cgo_export.h"

// Java supplies standard UTF-8, including supplementary characters in paths.
// Probes return fixed ASCII diagnostics, so JNI modified UTF-8 is sufficient for results.
static jstring probe_directory(JNIEnv *env, jbyteArray directory, int relay_only, int tailnet) {
    jsize length = (*env)->GetArrayLength(env, directory);
    jbyte *bytes = (*env)->GetByteArrayElements(env, directory, NULL);
    if (bytes == NULL) return NULL; // Pending allocation exception.
    char *report = tailnet ? klethron_tailnet_probe((char *) bytes, length, relay_only)
                           : klethron_startup_probe((char *) bytes, length);
    (*env)->ReleaseByteArrayElements(env, directory, bytes, JNI_ABORT);
    jstring result = (*env)->NewStringUTF(env, report);
    free(report);
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_klethron_tsnet_StartupTest_nativeProbe(JNIEnv *env, jclass type, jbyteArray directory) {
    return probe_directory(env, directory, 0, 0);
}

JNIEXPORT jstring JNICALL
Java_com_klethron_tsnet_TailnetTest_nativeProbe(JNIEnv *env, jclass type, jbyteArray directory, jboolean relayOnly) {
    return probe_directory(env, directory, relayOnly == JNI_TRUE, 1);
}
