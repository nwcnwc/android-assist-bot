# Keep app class and method names so the on-device log is readable.
# Shrinking still removes unused code. mapping.txt is kept for everything else.
-keep,allowshrinking,allowoptimization class io.github.nwcnwc.askbutton.** { *; }

-keepattributes SourceFile,LineNumberTable
