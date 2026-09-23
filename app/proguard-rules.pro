# 保留行号信息，便于定位崩溃堆栈
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# SNTP 客户端使用纯 JDK API，无需额外规则
-dontwarn java.net.**
