# Copperhead Gateway ProGuard rules
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep SIP message parsing
-keep class com.copperhead.gateway.sip.** { *; }
