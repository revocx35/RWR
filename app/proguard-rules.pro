# ML Kit discovers its components by reflection (ComponentDiscovery instantiates each registrar via its
# no-arg constructor). With current R8 these constructors are stripped and the face detector client is
# null at runtime, so keep them explicitly.
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
-keep class com.google.mlkit.**.*Registrar { <init>(); }
