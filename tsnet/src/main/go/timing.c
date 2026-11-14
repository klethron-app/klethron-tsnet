//go:build klethron_test

#include <jni.h>

static JavaVM *timing_vm;
static jclass timing_class;
static jmethodID timing_event;

JNIEXPORT void JNICALL
Java_com_klethron_tsnet_Timing_initialize(JNIEnv *env, jclass type) {
    (*env)->GetJavaVM(env, &timing_vm);
    timing_class = (*env)->NewGlobalRef(env, type);
    if (timing_class != NULL) timing_event = (*env)->GetStaticMethodID(env, type, "nativeEvent", "(JJII)V");
}

// Debug events are synchronous. No data is queued and no application callback is invoked.
void klethron_timing_event(long long node, long long span, int phase, int event) {
    if (timing_event == NULL) return;
    JNIEnv *env = NULL;
    int attached = (*timing_vm)->GetEnv(timing_vm, (void **) &env, JNI_VERSION_1_6) == JNI_EDETACHED;
    if (attached && (*timing_vm)->AttachCurrentThread(timing_vm, &env, NULL) != JNI_OK) return;
    if (env != NULL) {
        (*env)->CallStaticVoidMethod(env, timing_class, timing_event, (jlong) node, (jlong) span, (jint) phase, (jint) event);
        // Diagnostics must not change node behavior or print exception details.
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    }
    if (attached) (*timing_vm)->DetachCurrentThread(timing_vm);
}
