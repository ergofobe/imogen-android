# kotlinx.serialization keeps its serializers as synthetic members of the classes it
# generates them for; R8 has no way to see they are used and removes them.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.imogen.** {
    *** Companion;
}
-keepclasseswithmembers class com.imogen.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Ktor and OkHttp both reach for optional classes that are simply absent on Android.
-dontwarn org.slf4j.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn java.lang.management.**
-dontwarn kotlinx.coroutines.debug.**
