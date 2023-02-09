#ifndef NABTO_EDGE_ANDROID_DEMO_TUNNEL_VIDEO_H
#define NABTO_EDGE_ANDROID_DEMO_TUNNEL_VIDEO_H

#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <gst/gst.h>
#include <gst/pbutils/pbutils.h>
#include <gst/video/video.h>

#define UNUSED(x) (void)(x)
#define ARRAY_SIZE(array) (sizeof(array)/sizeof((array)[0]))

#define GST_CONTEXT_FIELD_NAME "gstContextPointer"
#define GST_SEEK_MIN (500 * GST_MSECOND)
#define GST_RTSPSRC_LATENCY 200

#define MIMETYPE_VIDEO_AV1   "video/av01"
#define MIMETYPE_VIDEO_AVC   "video/avc"
#define MIMETYPE_VIDEO_H263  "video/3gpp"
#define MIMETYPE_VIDEO_AVC   "video/avc"
#define MIMETYPE_VIDEO_HEVC  "video/hevc"
#define MIMETYPE_VIDEO_MPEG2 "video/mpeg2"
#define MIMETYPE_VIDEO_MPEG4 "video/mp4v-es"
#define MIMETYPE_VIDEO_VP8   "video/x-vnd.on2.vp8"
#define MIMETYPE_VIDEO_VP9   "video/x-vnd.on2.vp9"

typedef struct Context
{
    gboolean initialized;

    jobject app;
    GstElement* pipeline;
    GMainContext* context;
    GMainLoop* main_loop;
    ANativeWindow* native_window;
    GstDiscoverer* discoverer;

    GstState state;
    GstState target_state;

    GstClockTime last_seek_time;
    gboolean is_live;

    jmethodID on_gst_initialized_id;
    jmethodID on_gst_stream_info_id;
} Context;

typedef enum GstRTSPLowerTrans {
    GST_RTSP_LOWER_TRANS_UNKNOWN    = 0,        // Invalid
    GST_RTSP_LOWER_TRANS_UDP        = 1 << 0,   // Stream data over UDP
    GST_RTSP_LOWER_TRANS_UDP_MCAST  = 1 << 1,   // Stream data over UDP multicast
    GST_RTSP_LOWER_TRANS_TCP        = 1 << 2,   // Stream data over TCP
    GST_RTSP_LOWER_TRANS_HTTP       = 1 << 3,   // Stream data tunneled over HTTP
    GST_RTSP_LOWER_TRANS_TLS        = 1 << 4    // Encrypt TCP and HTTP with TLS
} GstRTSPLowerTrans;

typedef enum GstPlayFlags {
    GST_PLAY_FLAG_VIDEO = 1 << 0,
    GST_PLAY_FLAG_AUDIO = 1 << 1,
    GST_PLAY_FLAG_TEXT  = 1 << 2
} GstPlayFlags;

#endif //NABTO_EDGE_ANDROID_DEMO_TUNNEL_VIDEO_H
