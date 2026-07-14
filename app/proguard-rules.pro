# Keep native methods
-keepclassmembers class * {
    native <methods>;
}

# sherpa-onnx: the native library (libsherpa-onnx-jni.so) reads the *fields* of these config
# classes by name via JNI GetFieldID (e.g. "decodingMethod" on OfflineRecognizerConfig) and calls
# their constructors/getters reflectively. R8 renames fields/classes by default, so without this the
# native OfflineRecognizer fails to construct in minified release builds with
# "Failed to get field ID for decodingMethod" — on-device voice input silently never runs (works in
# unminified debug only). Keep the whole API surface so JNI name lookups resolve.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# Keep classes that are used as a parameter type of methods that are also marked as keep
# to preserve changing those methods' signature.
-keep class helium314.keyboard.latin.dictionary.Dictionary
-keep class helium314.keyboard.latin.NgramContext
-keep class helium314.keyboard.latin.makedict.ProbabilityInfo

# after upgrading to gradle 8, stack traces contain "unknown source"
-keepattributes SourceFile,LineNumberTable

# Tink (pulled in by androidx.security:security-crypto) references compile-only
# annotations that are not on the runtime classpath. These are safe to ignore.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.concurrent.**
-dontwarn com.google.j2objc.annotations.**

# MediaPipe LlmInference uses AutoValue annotations that are compile-only and absent at runtime.
-dontwarn com.google.auto.value.AutoValue
-dontwarn com.google.auto.value.AutoValue$Builder
