# YOLO26 NCNN ProGuard Rules

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep Yolo26Ncnn class
-keep class com.example.snapshop.Yolo26Ncnn { *; }
-keep class com.example.snapshop.Yolo26Ncnn$Obj { *; }
