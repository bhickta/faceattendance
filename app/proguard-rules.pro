# Project-specific R8 rules belong here.
-keep class org.opencv.** { *; }
-keep class com.bhickta.faceattendance.vision.OfflineBiometricEngine { public <init>(android.content.Context); }
