# R8 rules for the release build.
#
# These matter more than usual here: the app is installed on locked tablets
# that cannot easily be updated, so a shrinking bug that breaks JSON parsing
# would strand the tablets. The release APK is smoke-tested on a device
# before it ships.

-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault, *Annotation*

# --- kotlinx.serialization -------------------------------------------------
# Serializers are looked up reflectively through the generated Companion, so
# the members below must survive shrinking or every API response fails.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# Our request/response models, named by @SerialName and parsed reflectively.
-keep @kotlinx.serialization.Serializable class br.com.pulsefitness.kiosk.data.** { *; }
-keepclassmembers class br.com.pulsefitness.kiosk.data.** { *; }

# --- Retrofit --------------------------------------------------------------
# The API is a Kotlin interface with suspend functions; Retrofit reads its
# generic return types at runtime.
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface <1>
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-keep interface br.com.pulsefitness.kiosk.data.KioskApi { *; }

-dontwarn retrofit2.KotlinExtensions
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn javax.annotation.**

# --- OkHttp ----------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- Room / WorkManager ----------------------------------------------------
# Both instantiate generated classes by name.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**
-keep class * extends androidx.work.Worker { <init>(...); }
-keep class * extends androidx.work.ListenableWorker { <init>(...); }

# --- Device Owner ----------------------------------------------------------
# The receiver is referenced by name in the manifest and in the provisioning
# command; obfuscating it would break `dpm set-device-owner`.
-keep class br.com.pulsefitness.kiosk.kiosk.KioskDeviceAdminReceiver { *; }
-keep class br.com.pulsefitness.kiosk.kiosk.BootReceiver { *; }
-keep class br.com.pulsefitness.kiosk.MainActivity { *; }
-keep class br.com.pulsefitness.kiosk.KioskApplication { *; }
