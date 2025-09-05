#include <jni.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <vector>
#include <chrono>
#include <cstdint>
#include <algorithm> // std::clamp

#define TAG "CameraNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

// --------------------------------------------------
// Globals
// --------------------------------------------------
static ANativeWindow* gNativeWindow = nullptr;

// Java callback to hand NV21 to encoder
static jobject   gJavaActivity           = nullptr;
static jmethodID gOnProcessedFrameMethod = nullptr; // void onProcessedFrameFromNative(byte[] nv21, long tsUs)

// --------------------------------------------------
// Helpers
// --------------------------------------------------

static inline void YUV420ToARGB_Pixel(
        int Y, int U, int V,
        uint8_t& R, uint8_t& G, uint8_t& B)
{
    // YUV (BT.601) integer conversion
    int C = Y - 16;
    int D = U - 128;
    int E = V - 128;

    int r = (298 * C + 409 * E + 128) >> 8;
    int g = (298 * C - 100 * D - 208 * E + 128) >> 8;
    int b = (298 * C + 516 * D + 128) >> 8;

    R = (uint8_t)std::clamp(r, 0, 255);
    G = (uint8_t)std::clamp(g, 0, 255);
    B = (uint8_t)std::clamp(b, 0, 255);
}

/**
 * Convert YUV420 (three planes, arbitrary row/pixel strides) to flat ARGB (A=255).
 * outARGB size must be width*height.
 */
static void ConvertYUV420ToARGB(
        const jbyte* yData, const jbyte* uData, const jbyte* vData,
        int yRowStride, int uRowStride, int vRowStride,
        int uPixelStride, int vPixelStride,
        int width, int height,
        uint32_t* outARGB)
{
    for (int j = 0; j < height; ++j) {
        const int yRow = j * yRowStride;
        const int uvRow = (j / 2);
        for (int i = 0; i < width; ++i) {
            const int yIdx = yRow + i;

            // Sample chroma at half res
            const int uvCol = (i / 2);
            const int uIdx  = uvRow * uRowStride + uvCol * uPixelStride;
            const int vIdx  = uvRow * vRowStride + uvCol * vPixelStride;

            const int Y = (uint8_t) yData[yIdx];
            const int U = (uint8_t) uData[uIdx];
            const int V = (uint8_t) vData[vIdx];

            uint8_t R, G, B;
            YUV420ToARGB_Pixel(Y, U, V, R, G, B);

            outARGB[j * width + i] = 0xFF000000u | (uint32_t(R) << 16) | (uint32_t(G) << 8) | uint32_t(B);
        }
    }
}

/**
 * ARGB -> NV21 (Y plane + interleaved VU), bounds-safe.
 * width/height are the ARGB (and output) dimensions.
 */
static void ARGBtoNV21(const uint32_t* argb, uint8_t* nv21, int width, int height)
{
    uint8_t* yPlane = nv21;
    uint8_t* vuPlane = nv21 + (size_t)width * height;

    const int uvStride = width;   // NV21 VU stride = output width
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

            // Write VU on even rows/cols
            if ((j & 1) == 0 && (i & 1) == 0) {
                const int uvRow = j / 2;
                const int uvCol = i; // interleaved spans full width
                const size_t uvIndex = (size_t)uvRow * uvStride + uvCol;
                // guard +1
                if (uvRow < uvH && (uvCol + 1) < uvW) {
                    vuPlane[uvIndex + 0] = (uint8_t)V;
                    vuPlane[uvIndex + 1] = (uint8_t)U;
                }
            }
        }
    }
}

// --------------------------------------------------
// JNI: Surface / Java context
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
    gOnProcessedFrameMethod = env->GetMethodID(cls, "onProcessedFrameFromNative", "([BJ)V");

    LOGD("Java context set (callback cached=%s)", gOnProcessedFrameMethod ? "yes" : "no");
}

// --------------------------------------------------
// JNI: frame processing
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

    // Map Java arrays
    jbyte* yData = env->GetByteArrayElements(yArray, nullptr);
    jbyte* uData = env->GetByteArrayElements(uArray, nullptr);
    jbyte* vData = env->GetByteArrayElements(vArray, nullptr);

    // ---- Convert YUV -> ARGB (no rotation) ----
    const int outW = width;
    const int outH = height;

    std::vector<uint32_t> argb(outW * outH);
    ConvertYUV420ToARGB(
            yData, uData, vData,
            yRowStride, uRowStride, vRowStride,
            uPixelStride, vPixelStride,
            outW, outH,
            argb.data());

    // ---- Preview: draw ARGB into native window ----
    ANativeWindow_setBuffersGeometry(gNativeWindow, outW, outH, WINDOW_FORMAT_RGBA_8888);
    ANativeWindow_Buffer buffer;
    if (ANativeWindow_lock(gNativeWindow, &buffer, nullptr) == 0) {
        // Copy line-by-line respecting buffer.stride
        uint32_t* dst = static_cast<uint32_t*>(buffer.bits);
        const int dstStride = buffer.stride; // in pixels

        for (int j = 0; j < outH; ++j) {
            uint32_t* drow = dst + j * dstStride;
            const uint32_t* srow = argb.data() + j * outW;
            std::copy(srow, srow + outW, drow);
        }

        ANativeWindow_unlockAndPost(gNativeWindow);
    } else {
        LOGD("Failed to lock window");
    }

    // ---- ARGB -> NV21 for encoder callback ----
    const size_t yuvSize = static_cast<size_t>(outW) * static_cast<size_t>(outH) * 3 / 2;
    std::vector<uint8_t> nv21(yuvSize, 0);

    // Defensive logging (helps diagnose corruption)
    // LOGD("NV21 convert: outW=%d outH=%d yuvSize=%zu argbSize=%zu", outW, outH, yuvSize, argb.size());

    ARGBtoNV21(argb.data(), nv21.data(), outW, outH);

    // Send to Java if callback available
    if (gJavaActivity && gOnProcessedFrameMethod) {
        jbyteArray yuvArray = env->NewByteArray(static_cast<jsize>(yuvSize));
        if (yuvArray) {
            env->SetByteArrayRegion(yuvArray, 0, static_cast<jsize>(yuvSize),
                                    reinterpret_cast<const jbyte*>(nv21.data()));

            const jlong nowUs =
                    (jlong) std::chrono::duration_cast<std::chrono::microseconds>(
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
