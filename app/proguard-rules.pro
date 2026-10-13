# JNI entry points and the callbacks native code invokes by name.
-keep class io.github.doesthings.meshwise.engine.NativeLib { native <methods>; }
-keep class io.github.doesthings.meshwise.cad.CadNative { native <methods>; }
-keep interface io.github.doesthings.meshwise.engine.NativeProgress { *; }
-keep class * implements io.github.doesthings.meshwise.engine.NativeProgress { boolean onProgress(float); }
-keepclassmembers class * {
    native <methods>;
}
