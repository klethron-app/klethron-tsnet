//go:build klethron_test

#include <jni.h>
#include <stdlib.h>
#include "_cgo_export.h"

JNIEXPORT jstring JNICALL
Java_com_klethron_tsnet_TrustRootsTest_nativeProbe(JNIEnv *env, jclass type) {
    // The report contains hex fingerprints only, so it is ASCII.
    char *report = klethron_roots_probe();
    jstring result = (*env)->NewStringUTF(env, report);
    free(report);
    return result;
}
