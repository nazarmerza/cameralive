// This C++ code is the same as provided in the previous, extended response.
// It includes all necessary JNI exports, globals, and the updated
// gCaptureNextFrame logic within processFrameYUV to send BGRA data back to Java.

#include <jni.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <vector>
#include <chrono>
#include <cstdint>
#include <algorithm>
#include <cmath>
#include <map>
#include <string>

#define TAG "CameraNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

// --------------------------------------------------
// Globals
// --------------------------------------------------
static ANativeWindow* gNativeWindow = nullptr;
static jobject   gJavaActivity           = nullptr;
static jmethodID gOnProcessedFrameMethod = nullptr; // For Video (NV21)
static jmethodID gOnProcessedPhotoMethod = nullptr; // For Photo (BGRA data)
static int gPreviewDegrees = 0;
static bool gCaptureNextFrame = false;              // Flag to capture next frame

// The global pointer for the currently active filter
static const float (*gCurrentLUT)[33][33][33][3] = nullptr;
static std::map<std::string, const float (*)[33][33][33][3]> gFilterMap;

// Filter headers (assumed to be correct)
#include "filters/lutify/Amy.hpp"
#include "filters/lutify/Claire.hpp"
#include "filters/lutify/FBoost10.hpp"
#include "filters/lutify/FTone3.hpp"
#include "filters/lutify/PFilm2.hpp"
#include "filters/BlueArchitecture.hpp"
#include "filters/HardBoost.hpp"
#include "filters/LongBeachMorning.hpp"
#include "filters/LushGreen.hpp"
#include "filters/MagicHour.hpp"
#include "filters/NaturalBoost.hpp"
#include "filters/OrangeAndBlue.hpp"
#include "filters/SoftBlackAndWhite.hpp"
#include "filters/Waves.hpp"
#include "filters/BlueHour.hpp"
#include "filters/ColdChrome.hpp"
#include "filters/CrispAutumn.hpp"
#include "filters/DarkAndSomber.hpp"

// --------------------------------------------------
// Helpers (RotateARGB90 and ARGBtoNV21 implementations remain the same)
// --------------------------------------------------
static void RotateARGB90(
        const uint32_t* src,
        uint32_t* dst,
        int width,
        int height)
{
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            dst[x * height + (height - 1 - y)] = src[y * width + x];
        }
    }
}

static void ARGBtoNV21(const uint32_t* argb, uint8_t* nv21, int width, int height)
{
    uint8_t* yPlane = nv21;
    uint8_t* vuPlane = nv21 + (size_t)width * height;

    const int uvStride = width;
    const int uvH = height / 2;
    const int uvW = width;

    for (int j = 0; j < height; ++j) {
        const int yRowOff = j * width;

        for (int i = 0; i < width; ++i) {
            const uint32_t p = argb[yRowOff + i];
            const int r = (p >> 16) & 0xFF;
            const int g = (p >> 8)  & 0xFF;
            const int b =  p        & 0xFF;

            int Y = (( 66*r + 129*g +  25*b + 128) >> 8) + 16;
            int U = ((-38*r -  74*g + 112*b + 128) >> 8) + 128;
            int V = ((112*r -  94*g -  18*b + 128) >> 8) + 128;

            Y = std::clamp(Y, 0, 255);
            U = std::clamp(U, 0, 255);
            V = std::clamp(V, 0, 255);

            yPlane[yRowOff + i] = (uint8_t)Y;

            if ((j & 1) == 0 && (i & 1) == 0) {
                const int uvRow = j / 2;
                const int uvCol = i;
                const size_t uvIndex = (size_t)uvRow * uvStride + uvCol;
                if (uvRow < uvH && (uvCol + 1) < uvW) {
                    vuPlane[uvIndex + 0] = (uint8_t)V;
                    vuPlane[uvIndex + 1] = (uint8_t)U;
                }
            }
        }
    }
}

// --------------------------------------------------
// JNI: Surface / Java context / Rotation
// --------------------------------------------------

extern "C"
JNIEXPORT void JNICALL
Java_com_nm_cameralivefx_MainActivity_nativeSetSurface(
        JNIEnv* env, jobject /*thiz*/, jobject surface)
{
    if (gNativeWindow) {
        ANativeWindow_release(gNativeWindow);
        gNativeWindow = nullptr;
    }
    gNativeWindow = ANativeWindow_fromSurface(env, surface);
    LOGD("Surface set (gNativeWindow=%p)", gNativeWindow);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_nm_cameralivefx_MainActivity_nativeSetJavaContext(
        JNIEnv* env, jclass /*clazz*/, jobject activity)
{
    if (gJavaActivity) {
        env->DeleteGlobalRef(gJavaActivity);
        gJavaActivity = nullptr;
    }
    gJavaActivity = env->NewGlobalRef(activity);

    jclass cls = env->GetObjectClass(gJavaActivity);

    // Video/Encoder callback
    gOnProcessedFrameMethod = env->GetMethodID(cls, "onProcessedFrameFromNative", "([BJ)V");

    // Photo callback (BGRA data)
    gOnProcessedPhotoMethod = env->GetMethodID(cls, "onProcessedPhotoFromNative", "([B)V");

    LOGD("Java context set (Video callback cached=%s, Photo callback cached=%s)",
         gOnProcessedFrameMethod ? "yes" : "no",
         gOnProcessedPhotoMethod ? "yes" : "no");
}

extern "C"
JNIEXPORT void JNICALL
Java_com_nm_cameralivefx_MainActivity_nativeSetRotationDegrees(JNIEnv* env, jclass clazz, jint degrees) {
    gPreviewDegrees = degrees;
    LOGD("Preview rotation degrees set to %d", gPreviewDegrees);
}

// --------------------------------------------------
// JNI: Filter management (unchanged)
// --------------------------------------------------

extern "C"
JNIEXPORT void JNICALL
Java_com_nm_cameralivefx_MainActivity_nativeInitializeFilters(JNIEnv* env, jclass clazz) {
    LOGD("Initializing filters map...");
    gFilterMap["None"] = nullptr;
    gFilterMap["Amy"] = &Amy;
    gFilterMap["Claire"] = &Claire;
    gFilterMap["FBoost10"] = &FBoost10;
    gFilterMap["FTone3"] = &FTone3;
    gFilterMap["PFilm2"] = &PFilm2;
    gFilterMap["Blue Architecture"] = &BlueArchitecture;
    gFilterMap["HardBoost"] = &HardBoost;
    gFilterMap["LongBeachMorning"] = &LongBeachMorning;
    gFilterMap["LushGreen"] = &LushGreen;
    gFilterMap["MagicHour"] = &MagicHour;
    gFilterMap["NaturalBoost"] = &NaturalBoost;
    gFilterMap["OrangeAndBlue"] = &OrangeAndBlue;
    gFilterMap["SoftBlackAndWhite"] = &SoftBlackAndWhite;
    gFilterMap["Waves"] = &Waves;
    gFilterMap["BlueHour"] = &BlueHour;
    gFilterMap["ColdChrome"] = &ColdChrome;
    gFilterMap["CrispAutumn"] = &CrispAutumn;
    gFilterMap["DarkAndSomber"] = &DarkAndSomber;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_nm_cameralivefx_MainActivity_nativeSetCurrentFilter(JNIEnv* env, jclass clazz, jstring filterName) {
    const char *name = env->GetStringUTFChars(filterName, nullptr);
    auto it = gFilterMap.find(name);
    if (it != gFilterMap.end()) {
        gCurrentLUT = it->second;
        LOGD("Switched to filter: %s", name);
    } else {
        LOGD("Filter not found: %s", name);
        gCurrentLUT = nullptr;
    }
    env->ReleaseStringUTFChars(filterName, name);
}

// --------------------------------------------------
// JNI: Photo Capture Flag
// --------------------------------------------------

extern "C"
JNIEXPORT void JNICALL
Java_com_nm_cameralivefx_MainActivity_nativeCapturePhoto(JNIEnv* env, jobject thiz) {
    gCaptureNextFrame = true;
    LOGD("Photo capture requested. Will process next frame.");
}


// --------------------------------------------------
// JNI: frame processing (with photo capture logic)
// --------------------------------------------------
extern "C"
JNIEXPORT void JNICALL
Java_com_nm_cameralivefx_CameraHandler_processFrameYUV(
        JNIEnv* env, jobject /*thiz*/,
        jbyteArray yArray, jbyteArray uArray, jbyteArray vArray,
        jint yRowStride, jint uRowStride, jint vRowStride,
        jint uPixelStride, jint vPixelStride,
        jint width, jint height)
{
    if (!gNativeWindow) return;

    jbyte* yData = env->GetByteArrayElements(yArray, nullptr);
    jbyte* uData = env->GetByteArrayElements(uArray, nullptr);
    jbyte* vData = env->GetByteArrayElements(vArray, nullptr);

    // ---- 1. Convert YUV -> BGRA and apply LUT ----
    std::vector<uint32_t> bgra(static_cast<size_t>(width) * height);

    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
            int yIndex = y * yRowStride + x;
            int uvX = x / 2, uvY = y / 2;
            int uIndex = uvY * uRowStride + uvX * uPixelStride;
            int vIndex = uvY * vRowStride + uvX * vPixelStride;

            int Y = yData[yIndex] & 0xFF;
            int U = uData[uIndex] & 0xFF;
            int V = vData[vIndex] & 0xFF;

            int C = Y - 16;
            int D = U - 128;
            int E = V - 128;

            int R = std::clamp((298 * C + 409 * E + 128) >> 8, 0, 255);
            int G = std::clamp((298 * C - 100 * D - 208 * E + 128) >> 8, 0, 255);
            int B = std::clamp((298 * C + 516 * D + 128) >> 8, 0, 255);

            int filteredR = R;
            int filteredG = G;
            int filteredB = B;

            if (gCurrentLUT) {
                const int lutSize = 33;
                int r_idx = (R * (lutSize - 1)) / 255;
                int g_idx = (G * (lutSize - 1)) / 255;
                int b_idx = (B * (lutSize - 1)) / 255;

                const float* lut_color = (*gCurrentLUT)[b_idx][g_idx][r_idx];

                filteredR = static_cast<int>(std::clamp(lut_color[0] * 255.0f, 0.0f, 255.0f));
                filteredG = static_cast<int>(std::clamp(lut_color[1] * 255.0f, 0.0f, 255.0f));
                filteredB = static_cast<int>(std::clamp(lut_color[2] * 255.0f, 0.0f, 255.0f));
            }

            // Store as ARGB (0xAARRGGBB) -> memory is [B, G, R, A]
            bgra[static_cast<size_t>(y) * width + x] =
                    0xFF000000 | (static_cast<uint32_t>(filteredB) << 16) |
                    (static_cast<uint32_t>(filteredG) << 8) | static_cast<uint32_t>(filteredR);
        }
    }

    // ---- 2. Photo Capture Check (SEND RAW BGRA DATA TO JAVA) ----
    if (gCaptureNextFrame && gJavaActivity && gOnProcessedPhotoMethod) {
        gCaptureNextFrame = false;

        const size_t bgraSizeBytes = static_cast<size_t>(width) * height * sizeof(uint32_t);
        jbyteArray photoArray = env->NewByteArray(static_cast<jsize>(bgraSizeBytes));

        if (photoArray) {
            env->SetByteArrayRegion(photoArray, 0, static_cast<jsize>(bgraSizeBytes),
                                    reinterpret_cast<const jbyte*>(bgra.data()));

            env->CallVoidMethod(gJavaActivity, gOnProcessedPhotoMethod, photoArray);
            env->DeleteLocalRef(photoArray);
            LOGD("Photo frame captured and sent to Java for JPEG encoding. Size: %d x %d", width, height);
        } else {
            LOGD("Failed to allocate jbyteArray for photo.");
        }
    }

    // ---- 3. Preview: apply rotation and draw into native window ----
    std::vector<uint32_t> rotatedBgra;
    int drawW = width;
    int drawH = height;
    const uint32_t* finalDrawData = bgra.data();

    if (gPreviewDegrees == 90 || gPreviewDegrees == 270) {
        drawW = height;
        drawH = width;
        rotatedBgra.resize(static_cast<size_t>(drawW) * drawH);
        RotateARGB90(bgra.data(), rotatedBgra.data(), width, height);
        finalDrawData = rotatedBgra.data();
    }

    ANativeWindow_setBuffersGeometry(gNativeWindow, drawW, drawH, WINDOW_FORMAT_RGBA_8888);
    ANativeWindow_Buffer buffer;
    if (ANativeWindow_lock(gNativeWindow, &buffer, nullptr) == 0) {
        uint32_t* dst = static_cast<uint32_t*>(buffer.bits);
        const int dstStride = buffer.stride;

        for (int j = 0; j < drawH; ++j) {
            uint32_t* drow = dst + static_cast<size_t>(j) * dstStride;
            const uint32_t* srow = finalDrawData + static_cast<size_t>(j) * drawW;
            std::copy(srow, srow + drawW, drow);
        }

        ANativeWindow_unlockAndPost(gNativeWindow);
    } else {
        LOGD("Failed to lock window");
    }

    // ---- 4. YUV -> NV21 for encoder callback ----
    const size_t yuvSize = static_cast<size_t>(width) * height * 3 / 2;
    std::vector<uint8_t> nv21(yuvSize, 0);

    ARGBtoNV21(bgra.data(), nv21.data(), width, height);

    if (gJavaActivity && gOnProcessedFrameMethod) {
        jbyteArray yuvArray = env->NewByteArray(static_cast<jsize>(yuvSize));
        if (yuvArray) {
            env->SetByteArrayRegion(yuvArray, 0, static_cast<jsize>(yuvSize),
                                    reinterpret_cast<const jbyte*>(nv21.data()));
            const jlong nowUs = (jlong)std::chrono::duration_cast<std::chrono::microseconds>(
                    std::chrono::steady_clock::now().time_since_epoch()).count();
            env->CallVoidMethod(gJavaActivity, gOnProcessedFrameMethod, yuvArray, nowUs);
            env->DeleteLocalRef(yuvArray);
        }
    }

    // ---- Release JNI arrays ----
    env->ReleaseByteArrayElements(yArray, yData, JNI_ABORT);
    env->ReleaseByteArrayElements(uArray, uData, JNI_ABORT);
    env->ReleaseByteArrayElements(vArray, vData, JNI_ABORT);
}