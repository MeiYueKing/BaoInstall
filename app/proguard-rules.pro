# LSPosed 通过 assets/xposed_init 里的类名反射加载入口，
# R8 一旦改名或裁掉这个类，模块在 system_server 里就完全不会被执行。
-keep class bao.omk.xposed.** { *; }

# Xposed API 由宿主（LSPosed）在运行期提供，这里只有 compileOnly 引用
-dontwarn de.robv.android.xposed.**
-keep class de.robv.android.xposed.** { *; }

# 反射调用的系统类字段名（mRequest / intent）属于 framework，
# 字符串形式引用，不会被裁剪，无需额外规则。

# 保留行号，崩溃栈才有意义
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
