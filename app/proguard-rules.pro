-repackageclasses ''
-allowaccessmodification

-keep class app.rawline.mobile.** { *;}
-keep class moe.matsuri.nb4a.** { *;}
# OutboundFactory instantiates the profile types reflectively by class name
-keep class app.rawline.mobile.outbound.types.** { *; }

# Clean Kotlin
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    static void checkParameterIsNotNull(java.lang.Object, java.lang.String);
    static void checkExpressionValueIsNotNull(java.lang.Object, java.lang.String);
    static void checkNotNullExpressionValue(java.lang.Object, java.lang.String);
    static void checkReturnedValueIsNotNull(java.lang.Object, java.lang.String, java.lang.String);
    static void checkReturnedValueIsNotNull(java.lang.Object, java.lang.String);
    static void checkFieldIsNotNull(java.lang.Object, java.lang.String, java.lang.String);
    static void checkFieldIsNotNull(java.lang.Object, java.lang.String);
    static void checkNotNull(java.lang.Object);
    static void checkNotNull(java.lang.Object, java.lang.String);
    static void checkNotNullParameter(java.lang.Object, java.lang.String);
    static void throwUninitializedPropertyAccessException(java.lang.String);
}

# SnakeYaml
-keep class org.yaml.snakeyaml.** { *; }

# JSch's algorithm registry instantiates implementations by class name.
# This includes jce.Random before the first SSH connection is opened.
-keep class com.jcraft.jsch.jce.** { *; }
-keep class com.jcraft.jsch.bc.** { *; }
-keep class com.jcraft.jsch.jzlib.** { *; }
-keep class com.jcraft.jsch.jbcrypt.** { *; }
-keep class com.jcraft.jsch.CipherNone { *; }
-keep class com.jcraft.jsch.DH* { *; }
-keep class com.jcraft.jsch.UserAuthNone { *; }
-keep class com.jcraft.jsch.UserAuthPassword { *; }
-keep class com.jcraft.jsch.UserAuthKeyboardInteractive { *; }
-keep class com.jcraft.jsch.UserAuthPublicKey { *; }
-keep class com.jcraft.jsch.JSch
-keep class com.jcraft.jsch.Session
# Optional desktop agents, logging adapters and Kerberos are not used by VPS
# password/key authentication over TCP. Do not retain their implementations.
-dontwarn com.sun.jna.**
-dontwarn org.newsclub.net.unix.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.slf4j.**
-dontwarn org.ietf.jgss.**

-dontobfuscate
-keepattributes SourceFile

-dontwarn java.beans.BeanInfo
-dontwarn java.beans.FeatureDescriptor
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor
-dontwarn java.beans.Transient
-dontwarn java.beans.VetoableChangeListener
-dontwarn java.beans.VetoableChangeSupport
-dontwarn org.apache.harmony.xnet.provider.jsse.SSLParametersImpl
-dontwarn org.bouncycastle.jce.provider.BouncyCastleProvider
-dontwarn org.bouncycastle.jsse.BCSSLParameters
-dontwarn org.bouncycastle.jsse.BCSSLSocket
-dontwarn org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
-dontwarn org.openjsse.javax.net.ssl.SSLParameters
-dontwarn org.openjsse.javax.net.ssl.SSLSocket
-dontwarn org.openjsse.net.ssl.OpenJSSE
-dontwarn java.beans.PropertyVetoException
