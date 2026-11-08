//go:build klethron_test

#include <jni.h>
#include <stdlib.h>
#include "_cgo_export.h"

JNIEXPORT jstring JNICALL
Java_com_klethron_tsnet_StartupTest_nativeProbe(JNIEnv *env, jclass type, jbyteArray directory) {
    // Java supplies standard UTF-8, including supplementary characters in paths.
    jsize length = (*env)->GetArrayLength(env, directory);
    jbyte *bytes = (*env)->GetByteArrayElements(env, directory, NULL);
    if (bytes == NULL) return NULL; // Pending allocation exception.
    char *report = klethron_startup_probe((char *) bytes, length);
    (*env)->ReleaseByteArrayElements(env, directory, bytes, JNI_ABORT);
    jstring result = (*env)->NewStringUTF(env, report); // Fixed ASCII diagnostics.
    free(report);
    return result;
}
