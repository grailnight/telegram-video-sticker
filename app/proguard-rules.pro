# Called from libstickerenc.so by name.
-keep interface io.github.grailnight.videosticker.NativeEncoder$ProgressListener { *; }
-keepclassmembers class * implements io.github.grailnight.videosticker.NativeEncoder$ProgressListener {
    boolean onEncodeProgress(int, int);
}
