# Giữ nguyên các lớp UniFFI và JNA để bản Release R8 không làm hỏng cầu nối Rust
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class uniffi.mini_office_core.** { *; }
-dontwarn java.awt.**
-dontwarn javax.swing.**
