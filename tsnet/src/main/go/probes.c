//go:build klethron_test

#include <jni.h>
#include <stdlib.h>
#include "_cgo_export.h"

JNIEXPORT jstring JNICALL
Java_com_klethron_tsnet_Probe_nativeCall(JNIEnv *env, jclass type, jbyteArray call) {
    // Java encodes standard UTF-8, including supplementary characters in paths.
    jsize length = (*env)->GetArrayLength(env, call);
    jbyte *bytes = (*env)->GetByteArrayElements(env, call, NULL);
    if (bytes == NULL) return NULL; // Pending allocation exception.
    char *result = klethron_probe((char *) bytes, length);
    (*env)->ReleaseByteArrayElements(env, call, bytes, JNI_ABORT);
    // Results are ASCII, so JNI modified UTF-8 is sufficient.
    jstring string = (*env)->NewStringUTF(env, result);
    free(result);
    return string;
}
