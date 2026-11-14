-keepclasseswithmembernames class com.klethron.tsnet.TailnetNode {
    native <methods>;
}

-keepclassmembers class com.klethron.tsnet.TailnetNode$Store {
    byte[] read(byte[]);
    void write(byte[], byte[]);
    void localNetworkDenied(byte[], byte[]);
}
