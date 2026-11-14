# Only debug native libraries call this private callback.
-keep class com.klethron.tsnet.Timing {
    private static void nativeEvent(long, long, int, int);
    static native void initialize();
}
