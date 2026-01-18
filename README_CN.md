# NCNN Android YOLO26

基于 NCNN 框架的 YOLO26n 目标检测 Android 应用。

## 构建步骤

### 1. 导出 YOLO26n NCNN 模型

```python
from ultralytics import YOLO

# 加载模型
model = YOLO("yolo26n.pt")

# 导出为 NCNN 格式
model.export(format="ncnn")
```

将生成的 `yolo26n.param` 和 `yolo26n.bin` 文件复制到 `app/src/main/assets/` 目录。

### 2. 下载依赖库

#### NCNN

从 [ncnn releases](https://github.com/Tencent/ncnn/releases) 下载：
- `ncnn-YYYYMMDD-android-vulkan.zip`

解压到 `app/src/main/jni/` 目录，重命名为 `ncnn-android-vulkan`。

#### OpenCV Mobile

从 [opencv-mobile releases](https://github.com/nihui/opencv-mobile/releases) 下载：
- `opencv-mobile-4.10.0-android.zip`

解压到 `app/src/main/jni/` 目录。

### 3. 目录结构

确保 JNI 目录结构如下：

```
app/src/main/jni/
├── CMakeLists.txt
├── yolo.h
├── yolo.cpp
├── yolo26ncnn.cpp
├── ncnn-android-vulkan/
│   ├── arm64-v8a/
│   └── armeabi-v7a/
└── opencv-mobile-4.10.0-android/
    └── sdk/
```

### 4. 模型文件

将模型文件放入 assets 目录：

```
app/src/main/assets/
├── yolo26n.param
└── yolo26n.bin
```

### 5. 构建项目

使用 Android Studio 打开项目，或使用命令行：

```bash
./gradlew assembleDebug
```

## 注意事项

### 模型输出层名称

导出的 NCNN 模型输出层名称可能不同，需要根据实际的 `.param` 文件调整 `yolo.cpp` 中的输出层名称：

```cpp
ex.extract("output0", out);  // 可能需要修改为实际名称
ex.extract("output1", out);
ex.extract("output2", out);
```

查看 `.param` 文件末尾的输出层名称进行对应修改。

### GPU 加速

- 需要设备支持 Vulkan
- 小模型在 CPU 上可能比 GPU 更快
- 首次 GPU 推理会有 shader 编译延迟

## 自定义类别

如果使用自定义数据集训练的模型，需要修改 `yolo.h` 中的 `class_names` 数组。

## 参考项目

- [ncnn-android-yolov8](https://github.com/nihui/ncnn-android-yolov8)
- [ncnn-android-yolov11](https://github.com/gaoxumustwin/ncnn-android-yolov11)
- [NCNN](https://github.com/Tencent/ncnn)
