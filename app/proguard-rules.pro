# 本地记账 App —— R8 规则（release 已开启 isMinifyEnabled + isShrinkResources）
#
# 本项目纯本地、不用反射框架（无 Gson / Retrofit / 动态代理），绝大多数依赖都自带
# consumer 规则（Room、Compose、Coroutines），所以这里只需要极少量保留规则。

# 1) 注解与调试信息：注解用于 Room / Kotlin 元数据；SourceFile + LineNumberTable 保留行号，
#    线上崩溃堆栈可配合 app/build/outputs/mapping/release/mapping.txt 反混淆还原
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable

# 2) 枚举保留 values() / valueOf()：Room 生成的 DAO 用 Enum.valueOf(字符串) 反序列化
#    （如 TxnType / Direction / Freq / EndMode / CategoryType），避免被 R8 的枚举优化删掉
-keepclassmembers enum com.qingjizhang.app.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
