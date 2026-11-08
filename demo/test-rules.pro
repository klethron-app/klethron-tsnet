# AndroidX Test references these optional static-analysis annotations.
# Apply only to the test APK; consumer JNI keep rules must stand on their own.
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.MustBeClosed
