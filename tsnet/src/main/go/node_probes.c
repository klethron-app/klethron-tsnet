//go:build klethron_test

#include <jni.h>
#include <stdlib.h>
#include "_cgo_export.h"

enum probe { STARTUP, CONTROL, TAILNET, TAILNET_RELAY_ONLY, NETCHANGE_START, NETCHANGE_AWAIT };

// Java supplies standard UTF-8, including supplementary characters in paths.
// Probes return fixed ASCII diagnostics (and hex hashes), so JNI modified UTF-8 is sufficient for results.
static jstring probe_bytes(JNIEnv *env, jbyteArray argument, enum probe probe) {
    jsize length = (*env)->GetArrayLength(env, argument);
    jbyte *bytes = (*env)->GetByteArrayElements(env, argument, NULL);
    if (bytes == NULL) return NULL; // Pending allocation exception.
    char *chars = (char *) bytes, *report;
    switch (probe) {
        case STARTUP: report = klethron_startup_probe(chars, length); break;
        case CONTROL: report = klethron_control_probe(chars, length); break;
        case TAILNET: report = klethron_tailnet_probe(chars, length, 0); break;
        case TAILNET_RELAY_ONLY: report = klethron_tailnet_probe(chars, length, 1); break;
        case NETCHANGE_START: report = klethron_netchange_probe_start(chars, length); break;
        case NETCHANGE_AWAIT: report = klethron_netchange_probe_await(chars, length); break;
    }
    (*env)->ReleaseByteArrayElements(env, argument, bytes, JNI_ABORT);
    jstring result = (*env)->NewStringUTF(env, report);
    free(report);
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_klethron_tsnet_StartupTest_nativeProbe(JNIEnv *env, jclass type, jbyteArray directory) {
    return probe_bytes(env, directory, STARTUP);
}

JNIEXPORT jstring JNICALL
Java_com_klethron_tsnet_ControlPlaneTest_nativeProbe(JNIEnv *env, jclass type, jbyteArray directory) {
    return probe_bytes(env, directory, CONTROL);
}

JNIEXPORT jstring JNICALL
Java_com_klethron_tsnet_TailnetTest_nativeProbe(JNIEnv *env, jclass type, jbyteArray directory, jboolean relayOnly) {
    return probe_bytes(env, directory, relayOnly == JNI_TRUE ? TAILNET_RELAY_ONLY : TAILNET);
}

JNIEXPORT jstring JNICALL
Java_com_klethron_tsnet_NetworkChangeTest_nativeProbeStart(JNIEnv *env, jclass type, jbyteArray directory) {
    return probe_bytes(env, directory, NETCHANGE_START);
}

JNIEXPORT jstring JNICALL
Java_com_klethron_tsnet_NetworkChangeTest_nativeProbeAwait(JNIEnv *env, jclass type, jbyteArray interfaceName) {
    return probe_bytes(env, interfaceName, NETCHANGE_AWAIT);
}

JNIEXPORT void JNICALL
Java_com_klethron_tsnet_NetworkChangeTest_nativeProbeStop(JNIEnv *env, jclass type) {
    klethron_netchange_probe_stop();
}
