cmake_minimum_required(VERSION 3.22.1)

project(yolo26ncnn)

# 设置 ncnn 路径 - 请根据实际解压路径修改
set(ncnn_DIR ${CMAKE_SOURCE_DIR}/ncnn-android-vulkan/${ANDROID_ABI}/lib/cmake/ncnn)

# 设置 OpenCV 路径 - 请根据实际解压路径修改
set(OpenCV_DIR ${CMAKE_SOURCE_DIR}/opencv-mobile-4.10.0-android/sdk/native/jni)

find_package(ncnn REQUIRED)
find_package(OpenCV REQUIRED core imgproc)

# 添加共享库
add_library(yolo26ncnn SHARED
    yolo26ncnn.cpp
    yolo.cpp
)

# 链接库
target_link_libraries(yolo26ncnn
    ncnn
    ${OpenCV_LIBS}
    camera2ndk
    mediandk
    android
    log
    jnigraphics
)
