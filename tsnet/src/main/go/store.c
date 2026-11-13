#include <stdlib.h>
#include "store.h"

struct state_store {
    JavaVM *vm;
    jobject callback;
    jmethodID read, write;
};

state_store *state_new(JNIEnv *env, jobject callback) {
    state_store *store = calloc(1, sizeof *store);
    if (store == NULL) return NULL;
    (*env)->GetJavaVM(env, &store->vm);
    store->callback = (*env)->NewGlobalRef(env, callback);
    jclass type = store->callback == NULL ? NULL : (*env)->GetObjectClass(env, callback);
    if (store->callback != NULL && type != NULL) {
        store->read = (*env)->GetMethodID(env, type, "read", "([B)[B");
        if (store->read != NULL) store->write = (*env)->GetMethodID(env, type, "write", "([B[B)V");
    }
    if (type != NULL) (*env)->DeleteLocalRef(env, type);
    if (store->write != NULL) return store;
    if (store->callback != NULL) (*env)->DeleteGlobalRef(env, store->callback);
    free(store);
    return NULL;
}

static JNIEnv *attach(state_store *store, int *attached) {
    JNIEnv *env = NULL;
    *attached = (*store->vm)->GetEnv(store->vm, (void **) &env, JNI_VERSION_1_6) == JNI_EDETACHED;
    if (*attached && (*store->vm)->AttachCurrentThread(store->vm, &env, NULL) != JNI_OK) return NULL;
    return env;
}

static JNIEnv *begin(state_store *store, int *attached) {
    JNIEnv *env = attach(store, attached);
    if (env == NULL) return NULL;
    if ((*env)->PushLocalFrame(env, 4) == JNI_OK) return env;
    (*env)->ExceptionClear(env);
    if (*attached) (*store->vm)->DetachCurrentThread(store->vm);
    return NULL;
}

static int end(state_store *store, JNIEnv *env, int attached, int result) {
    // Callback exceptions may contain credentials. Never format, log or retain them.
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        result = -1;
    }
    (*env)->PopLocalFrame(env, NULL);
    if (attached) (*store->vm)->DetachCurrentThread(store->vm);
    return result;
}

static jbyteArray bytes(JNIEnv *env, const void *data, int length) {
    jbyteArray array = (*env)->NewByteArray(env, length);
    if (array != NULL && length > 0) (*env)->SetByteArrayRegion(env, array, 0, length, data);
    return array;
}

int state_read(state_store *store, const char *key, int key_length, void **data, int *length) {
    int attached, result = -1;
    JNIEnv *env = begin(store, &attached);
    if (env == NULL) return -1;
    jbyteArray name = bytes(env, key, key_length);
    if (name != NULL) {
        jbyteArray value = (*env)->CallObjectMethod(env, store->callback, store->read, name);
        if (!(*env)->ExceptionCheck(env)) {
            result = 1;
            if (value != NULL) {
                *length = (*env)->GetArrayLength(env, value);
                *data = malloc(*length == 0 ? 1 : (size_t) *length);
                result = -1;
                if (*data != NULL) {
                    (*env)->GetByteArrayRegion(env, value, 0, *length, *data);
                    result = 0;
                }
            }
        }
    }
    return end(store, env, attached, result);
}

int state_write(state_store *store, const char *key, int key_length, const void *data, int length) {
    int attached;
    JNIEnv *env = begin(store, &attached);
    if (env == NULL) return -1;
    jbyteArray name = bytes(env, key, key_length);
    if (name != NULL) {
        jbyteArray value = length < 0 ? NULL : bytes(env, data, length);
        if (!(*env)->ExceptionCheck(env)) (*env)->CallVoidMethod(env, store->callback, store->write, name, value);
    }
    return end(store, env, attached, 0);
}

void state_close(state_store *store) {
    int attached;
    JNIEnv *env = attach(store, &attached);
    if (env != NULL) {
        (*env)->DeleteGlobalRef(env, store->callback);
        if (attached) (*store->vm)->DetachCurrentThread(store->vm);
    }
    free(store);
}
