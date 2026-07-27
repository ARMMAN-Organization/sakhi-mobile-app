-keep class net.sqlcipher.** { *; }
-keepattributes Signature, *Annotation*

# Tink references errorprone annotations that aren't on the runtime classpath
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi
