#include <string.h>
#include <jni.h>
#include <android/log.h>
#include <gst/gst.h>

static const char* TAG = "TunnelVideoNative";

JNIEXPORT jstring JNICALL
Java_com_nabto_edge_tunnelvideodemo_TunnelVideoApplication_nativeGetGStreamerInfo(JNIEnv *env,
                                                                                  jobject thiz) {
    char* version_utf8 = gst_version_string();
    jstring* version_jstring = (*env)->NewStringUTF(env, version_utf8);
    g_free(version_utf8);
    return version_jstring;
}