#include <stddef.h>
#include <jni.h>

typedef struct state_store state_store;
state_store *state_new(JNIEnv *, jobject);
// Read: 0 = present, 1 = absent, -1 = failure. Caller frees the returned bytes.
int state_read(state_store *, const char *, int, void **, int *);
int state_write(state_store *, const char *, int, const void *, int);
int state_local_network_denied(state_store *, const void *, int, const void *, int);
void state_close(state_store *);
