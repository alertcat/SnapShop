#include <android/asset_manager_jni.h>
#include <android/native_window_jni.h>
#include <android/native_window.h>
#include <android/log.h>
#include <jni.h>
#include <string>
#include <vector>

#include <platform.h>
#include <benchmark.h>

#include "yolo.h"

#include <opencv2/core/core.hpp>
#include <opencv2/imgproc/imgproc.hpp>

#if __ARM_NEON
#include <arm_neon.h>
#endif

static Yolo* g_yolo = 0;
static ncnn::Mutex lock;

// YOLO26n 配置
static const int YOLO26_TARGET_SIZE = 640;
static const float YOLO26_MEAN_VALS[3] = {0.f, 0.f, 0.f};
static const float YOLO26_NORM_VALS[3] = {1 / 255.f, 1 / 255.f, 1 / 255.f};

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    __android_log_print(ANDROID_LOG_DEBUG, "Yolo26Ncnn", "JNI_OnLoad");
    return JNI_VERSION_1_4;
}

JNIEXPORT void JNI_OnUnload(JavaVM* vm, void* reserved) {
    __android_log_print(ANDROID_LOG_DEBUG, "Yolo26Ncnn", "JNI_OnUnload");

    ncnn::MutexLockGuard g(lock);
    delete g_yolo;
    g_yolo = 0;
}

JNIEXPORT jboolean JNICALL Java_com_example_snapshop_Yolo26Ncnn_loadModel(JNIEnv* env, jobject thiz, jobject assetManager, jint modelid, jint useGpu) {
    if (modelid < 0 || modelid > 0) {
        return JNI_FALSE;
    }

    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);

    __android_log_print(ANDROID_LOG_DEBUG, "Yolo26Ncnn", "loadModel %p", mgr);

    const char* modeltype = "yolo26n";
    bool use_gpu = (useGpu == 1);

    {
        ncnn::MutexLockGuard g(lock);

        // Check GPU availability
        if (use_gpu && ncnn::get_gpu_count() == 0) {
            __android_log_print(ANDROID_LOG_WARN, "Yolo26Ncnn", "GPU not available, falling back to CPU");
            use_gpu = false;
        }

        if (!g_yolo) {
            g_yolo = new Yolo;
        }

        const char* device_name = use_gpu ? "GPU (FP32)" : "CPU";
        __android_log_print(ANDROID_LOG_DEBUG, "Yolo26Ncnn", "Loading model: %s on %s", modeltype, device_name);
        g_yolo->load(mgr, modeltype, YOLO26_TARGET_SIZE, YOLO26_MEAN_VALS, YOLO26_NORM_VALS, use_gpu);
        __android_log_print(ANDROID_LOG_DEBUG, "Yolo26Ncnn", "Model loaded successfully");
    }

    return JNI_TRUE;
}

JNIEXPORT jobjectArray JNICALL Java_com_example_snapshop_Yolo26Ncnn_detect(JNIEnv* env, jobject thiz, jobject bitmap) {
    double start_time = ncnn::get_current_time();

    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888)
        return NULL;

    // Lock bitmap pixels
    void* indata;
    AndroidBitmap_lockPixels(env, bitmap, &indata);

    // RGBA to BGR (model expects BGR input)
    cv::Mat rgba(info.height, info.width, CV_8UC4, indata);
    cv::Mat bgr;
    cv::cvtColor(rgba, bgr, cv::COLOR_RGBA2BGR);

    AndroidBitmap_unlockPixels(env, bitmap);

    // Detection
    std::vector<Object> objects;
    {
        ncnn::MutexLockGuard g(lock);

        if (g_yolo) {
            g_yolo->detect(bgr, objects);
        }
    }

    // Create result array
    jclass objCls = env->FindClass("com/example/snapshop/Yolo26Ncnn$Obj");
    jmethodID objInit = env->GetMethodID(objCls, "<init>", "(Lcom/example/snapshop/Yolo26Ncnn;)V");
    jfieldID xId = env->GetFieldID(objCls, "x", "F");
    jfieldID yId = env->GetFieldID(objCls, "y", "F");
    jfieldID wId = env->GetFieldID(objCls, "w", "F");
    jfieldID hId = env->GetFieldID(objCls, "h", "F");
    jfieldID labelId = env->GetFieldID(objCls, "label", "Ljava/lang/String;");
    jfieldID probId = env->GetFieldID(objCls, "prob", "F");

    jobjectArray jObjArray = env->NewObjectArray(objects.size(), objCls, NULL);

    for (size_t i = 0; i < objects.size(); i++) {
        jobject jObj = env->NewObject(objCls, objInit, thiz);

        env->SetFloatField(jObj, xId, objects[i].rect.x);
        env->SetFloatField(jObj, yId, objects[i].rect.y);
        env->SetFloatField(jObj, wId, objects[i].rect.width);
        env->SetFloatField(jObj, hId, objects[i].rect.height);

        // 边界检查防止越界访问
        int label = objects[i].label;
        const char* label_name = (label >= 0 && label < 80) ? class_names[label] : "unknown";
        env->SetObjectField(jObj, labelId, env->NewStringUTF(label_name));
        env->SetFloatField(jObj, probId, objects[i].prob);

        env->SetObjectArrayElement(jObjArray, i, jObj);
    }

    double elasped = ncnn::get_current_time() - start_time;
    __android_log_print(ANDROID_LOG_DEBUG, "Yolo26Ncnn", "%.2fms detect", elasped);

    return jObjArray;
}

}
