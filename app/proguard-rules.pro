# The release build is shrunk by R8. Kept as they are:

# WebRTC: its native code finds these classes, methods and fields by name
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
# ... and its link between native code and Java (without it, starting a stream crashed in native code)
-keep class org.jni_zero.** { *; }
-dontwarn org.jni_zero.**

# Keep line numbers in crash reports readable
-keepattributes SourceFile,LineNumberTable
