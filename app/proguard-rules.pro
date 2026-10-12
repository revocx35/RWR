# ML Kit discovers its components by reflection (ComponentDiscovery instantiates each registrar via its
# no-arg constructor). With current R8 these constructors are stripped and the face detector client is
# null at runtime, so keep them explicitly.
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
-keep class com.google.mlkit.**.*Registrar { <init>(); }

# MediaPipe Tasks (eye tracking): its JNI layer and protobuf-lite messages are accessed by reflection
# (field names like platform_ are looked up at runtime). Without these the landmarker fails to start
# in release builds only ("Field platform_ for ... not found").
-keep class com.google.mediapipe.** { *; }
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
-dontwarn com.google.mediapipe.**
# MediaPipe logs through Flogger, which finds its caller by walking the stack for its own class names.
-keep class com.google.common.flogger.** { *; }
-dontwarn com.google.common.flogger.**
