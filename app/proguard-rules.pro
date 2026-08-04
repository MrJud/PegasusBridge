# The Bridge's own classes are the IPC surface: services are named in the
# manifest and reached by Intent, so renaming them breaks the contract.
-keep class com.pegasus.bridge.** { *; }
-keep class org.json.** { *; }
-dontwarn okhttp3.**

# ── NewPipeExtractor ────────────────────────────────────────────────────────
#
# It runs YouTube's own JavaScript through Rhino to resolve stream URLs, so the
# classes Rhino reaches reflectively cannot be renamed or stripped — R8 has no
# way to see those references. Without this the release build compiles and then
# fails at runtime, only on video, which is the worst way to find out.
-keep class org.mozilla.javascript.** { *; }
# ...except the JSR-223 wrapper, which is a bridge to javax.script — an API
# Android does not have. Keeping it only drags in classes that cannot exist.
-dontwarn org.mozilla.javascript.engine.**
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.jsoup.** { *; }

# Optional dependencies these libraries reference but never ship: re2j is an
# alternative regex engine for jsoup, java.beans and javax.script do not exist
# on Android, and the xz filters are unused compression options.
-dontwarn com.google.re2j.Matcher
-dontwarn com.google.re2j.Pattern
-dontwarn java.beans.BeanDescriptor
-dontwarn java.beans.BeanInfo
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor
-dontwarn javax.script.**
-dontwarn org.tukaani.xz.**

# Rhino's optimising compiler links call sites through jdk.dynalink, a JVM-only
# API. On Android it falls back to the interpreter, which is the path
# NewPipeExtractor uses anyway. jspecify is compile-time annotations only.
-dontwarn jdk.dynalink.**
-dontwarn org.jspecify.annotations.**
