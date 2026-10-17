# libxposed 现代 API 要求：保留模块入口类，并重写入口清单
-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

# 设置页与设置提供者需要保留
-keep class io.relimus.zflow.compat.ui.** { *; }
-keep class io.relimus.zflow.compat.settings.** { *; }

# 模块入口清单与 scope 清单原样保留
-keepattributes *Annotation*
