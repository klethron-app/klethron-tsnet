//go:build klethron_test

#include <jni.h>
#include <stdlib.h>
#include "_cgo_export.h"

JNIEXPORT jstring JNICALL
Java_com_klethron_tsnet_NetworkInterfacesTest_nativeProbe(JNIEnv *env, jclass type) {
    // Interface names and addresses are hex-encoded; the report is ASCII.
    char *report = klethron_network_probe();
    jstring result = (*env)->NewStringUTF(env, report);
    free(report);
    return result;
}
