# ─── PalmAnnotate ProGuard Rules ──────────────────────────────────────────────

# Orbbec SDK — JNI + reflection, must not be stripped
-keep class com.orbbec.** { *; }
-keepclassmembers class com.orbbec.** { *; }
-dontwarn com.orbbec.**

# ONNX Runtime
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Keep data classes used in serialization
-keep class dev.sawitulm.palmannotate.domain.model.** { *; }
-keep class dev.sawitulm.palmannotate.data.export.** { *; }
