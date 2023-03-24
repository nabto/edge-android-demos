#include "tunnel_video.h"
#include <gst/gsttypefind.h>
#include <string.h>

/*****************************************************************************
 * tunnel_video.c
 *   Currently contains all code related to interfacing GStreamer with
 *   Android through JNI. Functions that are prefaced with gst are
 *   exported to Kotlin.
 *****************************************************************************/

GST_DEBUG_CATEGORY_STATIC(debug_category);
#define GST_CAT_DEFAULT debug_category

static jfieldID g_context_field_id;
#if GLIB_SIZEOF_VOID_P == 8
#define GET_CONTEXT(env, this) (Context*)(*(env))->GetLongField((env), (this), g_context_field_id)
#define SET_CONTEXT(env, this, data) (*(env))->SetLongField((env), (this), g_context_field_id, (jlong)(data))
#else
#define GET_CONTEXT(env, this) (Context*)(*(env))->GetLongField((env), (this), g_context_field_id)
#define SET_CONTEXT(env, this, data) (*(env))->SetLongField((env), (this), g_context_field_id, (jlong)(jint)(data))
#endif

static const char* TAG = "TunnelVideoNative";
static JavaVM* jvm;
static pthread_t gst_app_thread;
static pthread_key_t current_jni_env;

static JNIEnv* attach_current_thread(void)
{
    JNIEnv* env;
    JavaVMAttachArgs args = {0};

    GST_DEBUG("Attaching thread %p", g_thread_self());
    args.version = JNI_VERSION_1_4;

    if ((*jvm)->AttachCurrentThread(jvm, &env, &args) < 0)
    {
        GST_ERROR("Failed to attach current thread.");
        return NULL;
    }

    return env;
}

static void detach_current_thread(void* env)
{
    UNUSED(env);
    GST_DEBUG("Detaching thread %p", g_thread_self());
    (*jvm)->DetachCurrentThread(jvm);
}

static JNIEnv* get_jni_env(void)
{
    JNIEnv* env = pthread_getspecific(current_jni_env);
    if (env == NULL)
    {
        env = attach_current_thread();
        pthread_setspecific(current_jni_env, env);
    }
    return env;
}

static const gchar* caps_to_mime(GstCaps* caps)
{
    const gchar* result = NULL;
    GstStructure* structure = gst_caps_get_structure(caps, 0);
    if (!structure)
    {
        return result;
    }

    const gchar* name = gst_structure_get_name(structure);
    const gchar* prefix = "video/";
    if (strncmp(name, prefix, strlen(prefix)) != 0)
    {
        return result;
    }

    if (strcmp(name, "video/mpeg") == 0) {
        gint mpeg_version;
        if (gst_structure_get_int(structure, "mpegversion", &mpeg_version))
        {
            if (mpeg_version == 4)
            {
                result = MIMETYPE_VIDEO_MPEG4;
            }
            else if (mpeg_version == 1 || mpeg_version == 2)
            {
                result = MIMETYPE_VIDEO_MPEG2;
            }
        }
    }
    else if (strcmp(name, "video/x-h263") == 0)
    {
        result = MIMETYPE_VIDEO_H263;
    }
    else if (strcmp(name, "video/x-h264") == 0)
    {
        result = MIMETYPE_VIDEO_AVC;
    }
    else if (strcmp(name, "video/x-h265") == 0)
    {
        result = MIMETYPE_VIDEO_HEVC;
    }
    else if (strcmp(name, "video/x-av1") == 0)
    {
        result = MIMETYPE_VIDEO_AV1;
    }
    else if (strcmp(name, "video/x-vp8") == 0)
    {
        result = MIMETYPE_VIDEO_VP8;
    }
    else if (strcmp(name, "video/x-vp9") == 0)
    {
        result = MIMETYPE_VIDEO_VP9;
    }
    else if (strcmp(name, "video/x-divx") == 0)
    {
        result = MIMETYPE_VIDEO_MPEG4;
    }

    return result;
}

static void seek(Context* ctx, gint64 desired_position)
{
    if (desired_position == GST_CLOCK_TIME_NONE)
    {
        return;
    }

    gint64 diff = (gint64)(gst_util_get_timestamp() - ctx->last_seek_time);
    if (!(GST_CLOCK_TIME_IS_VALID(ctx->last_seek_time) && diff < GST_SEEK_MIN))
    {
        GST_DEBUG("Seeking to %" GST_TIME_FORMAT, GST_TIME_ARGS(desired_position));
        ctx->last_seek_time = gst_util_get_timestamp();
        gst_element_seek_simple(ctx->pipeline, GST_FORMAT_TIME, GST_SEEK_FLAG_FLUSH | GST_SEEK_FLAG_KEY_UNIT, desired_position);
    }
}

static void pipeline_source_setup_callback(GstBus* bus, GstElement* source, Context* ctx)
{
    UNUSED(bus);
    UNUSED(ctx);
    const gchar* source_name = g_type_name(G_TYPE_FROM_INSTANCE(G_OBJECT(source)));
    if (g_str_equal("GstRTSPSrc", source_name))
    {
        g_object_set(source, "latency", GST_RTSPSRC_LATENCY, NULL);
        g_object_set(source, "protocols", GST_RTSP_LOWER_TRANS_TCP, NULL);
        // enable to forcibly drop frames when latency is higher than GST_RTSPSRC_LATENCY
        //g_object_set(source, "drop-on-latency", TRUE, NULL);
    }
}

static void error_callback(GstBus* bus, GstMessage* msg, Context* ctx)
{
    UNUSED(bus);
    GError* err;
    gchar* debug_info;

    gst_message_parse_error(msg, &err, &debug_info);
    // @TODO: Bubble this up to the UI.
    GST_DEBUG("Error received from Gst element %s: %s", GST_OBJECT_NAME(msg->src), err->message);
    g_clear_error(&err);
    g_free(debug_info);
    gst_element_set_state(ctx->pipeline, GST_STATE_NULL);
}

static void end_of_stream_callback(GstBus* bus, GstMessage* msg, Context* ctx)
{
    UNUSED(bus);
    UNUSED(msg);
    ctx->target_state = GST_STATE_PAUSED;
    ctx->is_live = (gst_element_set_state(ctx->pipeline, GST_STATE_PAUSED) == GST_STATE_CHANGE_NO_PREROLL);
    seek(ctx, 0);
}

static void clock_lost_callback(GstBus* bus, GstMessage* msg, Context* ctx)
{
    UNUSED(bus);
    UNUSED(msg);
    if (ctx->target_state >= GST_STATE_PLAYING)
    {
        gst_element_set_state(ctx->pipeline, GST_STATE_PAUSED);
        gst_element_set_state(ctx->pipeline, GST_STATE_PLAYING);
    }
}

static void stream_collection_callback(GstBus* bus, GstMessage* msg, Context* ctx)
{
    UNUSED(bus);
    GstStreamCollection* collection = NULL;
    gst_message_parse_stream_collection(msg, &collection);
    guint size = gst_stream_collection_get_size(collection);
    for (int i = 0; i < size; i++)
    {
        GstStream* stream = gst_stream_collection_get_stream(collection, i);
        GstCaps* caps = gst_stream_get_caps(stream);
        if (caps)
        {
            guint cap_count = gst_caps_get_size(caps);
            for (int cap_index = 0; cap_index < cap_count; cap_index++)
            {
                const gchar* mime = caps_to_mime(caps);
                if (mime)
                {
                    JNIEnv* env = get_jni_env();
                    jstring jmime = (*env)->NewStringUTF(env, mime);
                    (*env)->CallVoidMethod(env, ctx->app, ctx->on_gst_stream_info_id, jmime);
                    if ((*env)->ExceptionCheck(env))
                    {
                        GST_ERROR("Failed to inform Kotlin of stream information.");
                        (*env)->ExceptionClear(env);
                    }
                    (*env)->DeleteLocalRef(env, jmime);
                }
            }
            gst_caps_unref(caps);
        }
        gst_object_unref(stream);
    }
}

static void state_changed_callback(GstBus* bus, GstMessage* msg, Context* ctx)
{
    UNUSED(bus);
    GstState old_state, new_state, pending_state;
    gst_message_parse_state_changed(msg, &old_state, &new_state, &pending_state);

    // Messages may come from children of the pipeline.
    // Ensure we only check messages from the actual pipeline.
    if (GST_MESSAGE_SRC(msg) == GST_OBJECT(ctx->pipeline))
    {
        ctx->state = new_state;
        __android_log_print(ANDROID_LOG_INFO, TAG, "GStreamer state changed to %s", gst_element_state_get_name(new_state));
    }
}

static void buffering_callback(GstBus* bus, GstMessage* msg, Context* ctx)
{
    // Live streams do not buffer.
    if (ctx->is_live)
    {
        return;
    }

    gint percent;
    gst_message_parse_buffering(msg, &percent);
    if (percent < 100 && ctx->target_state >= GST_STATE_PAUSED)
    {
        gst_element_set_state(ctx->pipeline, GST_STATE_PAUSED);
        // @TODO: Media is buffering and the pipeline is paused, we should
        //        display a message or a spinner to signify buffering.
    }
    else if (ctx->target_state >= GST_STATE_PLAYING)
    {
        // Buffering percentage is >= 100, we can continue playing the media.
        gst_element_set_state(ctx->pipeline, GST_STATE_PLAYING);
    }
}

static void check_initialization(Context* ctx)
{
    JNIEnv* env = get_jni_env();
    if (!ctx->initialized && ctx->native_window && ctx->main_loop)
    {
        GST_DEBUG("Initialization complete, notifying application. native_window::%p main_loop::%p", ctx->native_window, ctx->main_loop);

        gst_video_overlay_set_window_handle(GST_VIDEO_OVERLAY(ctx->pipeline), (guintptr)ctx->native_window);
        (*env)->CallVoidMethod(env, ctx->app, ctx->on_gst_initialized_id);
        if ((*env)->ExceptionCheck(env))
        {
            GST_ERROR("Failed to call Java method");
            (*env)->ExceptionClear(env);
        }

        ctx->initialized = TRUE;
    }
}

static void* app_main(void* userdata)
{
    GstBus* bus;
    Context* ctx = (Context*)userdata;
    GSource* bus_source;
    GError* error = NULL;

    GST_DEBUG("Creating pipeline in Context at %p", ctx);

    ctx->context = g_main_context_new();
    g_main_context_push_thread_default(ctx->context);

    ctx->pipeline = gst_parse_launch("playbin3", &error);
    if (error)
    {
        // @TODO: Log the error->message
        g_clear_error(&error);
        return NULL;
    }
    g_signal_connect(ctx->pipeline, "source-setup", G_CALLBACK(pipeline_source_setup_callback), ctx);

    ctx->target_state = GST_STATE_READY;
    gst_element_set_state(ctx->pipeline, ctx->target_state);

    // get the pipeline's bus to forward messages from the streaming threads to our callbacks.
    bus = gst_element_get_bus(ctx->pipeline);
    bus_source = gst_bus_create_watch(bus);
    g_source_set_callback(bus_source, (GSourceFunc) gst_bus_async_signal_func, NULL, NULL);
    g_source_attach(bus_source, ctx->context);
    g_source_unref(bus_source);
    g_signal_connect(G_OBJECT(bus), "message::error",             G_CALLBACK(error_callback), ctx);
    g_signal_connect(G_OBJECT(bus), "message::state-changed",     G_CALLBACK(state_changed_callback), ctx);
    g_signal_connect(G_OBJECT(bus), "message::eos",               G_CALLBACK(end_of_stream_callback), ctx);
    g_signal_connect(G_OBJECT(bus), "message::clock-lost",        G_CALLBACK(clock_lost_callback), ctx);
    g_signal_connect(G_OBJECT(bus), "message::buffering",         G_CALLBACK(buffering_callback), ctx);
    g_signal_connect(G_OBJECT(bus), "message::stream-collection", G_CALLBACK(stream_collection_callback), ctx);
    gst_object_unref(bus);

    GST_DEBUG("Entering main loop with context::%p", ctx);
    ctx->main_loop = g_main_loop_new(ctx->context, FALSE);
    check_initialization(ctx);
    g_main_loop_run(ctx->main_loop);
    GST_DEBUG("Exited main loop");
    g_main_loop_unref(ctx->main_loop);
    ctx->main_loop = NULL;

    // Clean up and return
    g_main_context_pop_thread_default(ctx->context);
    g_main_context_unref(ctx->context);
    ctx->target_state = GST_STATE_NULL;
    gst_element_set_state(ctx->pipeline, ctx->target_state);
    gst_object_unref(ctx->pipeline);

    return NULL;
}

static void gst_controller_start(JNIEnv* env, jobject this)
{
    Context* ctx = GET_CONTEXT(env, this);
    if (!ctx)
    {
        return;
    }

    GST_DEBUG_CATEGORY_INIT(debug_category, TAG, 0, "Nabto Tunnel Video Native");
    gst_debug_set_threshold_for_name(TAG, GST_LEVEL_DEBUG);

    ctx->app = (*env)->NewGlobalRef(env, this);
    GST_DEBUG("Created GlobalRef for app object at %p", ctx->app);

    ctx->last_seek_time = GST_CLOCK_TIME_NONE;
    pthread_create(&gst_app_thread, NULL, &app_main, ctx);
}

static void gst_media_set_uri(JNIEnv* env, jobject this, jstring uri)
{
    Context* ctx = GET_CONTEXT(env, this);
    if (!ctx || !ctx->pipeline)
    {
        return;
    }

    const gchar* uri_string = (*env)->GetStringUTFChars(env, uri, NULL);
    GST_DEBUG("Media player setting URI to %s", uri_string);
    if (ctx->target_state >= GST_STATE_READY)
    {
        gst_element_set_state(ctx->pipeline, GST_STATE_READY);
    }
    g_object_set(ctx->pipeline, "uri", uri_string, NULL);
    (*env)->ReleaseStringUTFChars(env, uri, uri_string);
    ctx->is_live = (gst_element_set_state(ctx->pipeline, ctx->target_state) == GST_STATE_CHANGE_NO_PREROLL);
}

static void gst_finalize(JNIEnv* env, jobject this)
{
    Context* ctx = GET_CONTEXT(env, this);
    if (!ctx)
    {
        return;
    }

    GST_DEBUG("Quitting main loop...");
    g_main_loop_quit(ctx->main_loop);
    GST_DEBUG("Waiting for thread to finish...");
    pthread_join(gst_app_thread, NULL);
    GST_DEBUG("Deleting GlobalRef for app object at %p", ctx->app);
    (*env)->DeleteGlobalRef(env, ctx->app);
    GST_DEBUG("Freeing context at %p", ctx);
    g_free(ctx);
    SET_CONTEXT(env, this, NULL);
}

static void gst_media_play(JNIEnv* env, jobject this)
{
    Context* ctx = GET_CONTEXT(env, this);
    if (!ctx)
    {
        return;
    }

    GST_DEBUG("Setting state to PLAYING.");
    ctx->target_state = GST_STATE_PLAYING;
    ctx->is_live = (gst_element_set_state(ctx->pipeline, ctx->target_state) == GST_STATE_CHANGE_NO_PREROLL);
}

static void gst_media_pause(JNIEnv* env, jobject this)
{
    Context* ctx = GET_CONTEXT(env, this);
    if (!ctx)
    {
        return;
    }

    GST_DEBUG("Setting state to PAUSED");
    ctx->target_state = GST_STATE_PAUSED;
    ctx->is_live = (gst_element_set_state(ctx->pipeline, ctx->target_state) == GST_STATE_CHANGE_NO_PREROLL);
}

static void gst_surface_init(JNIEnv* env, jobject this, jobject surface)
{
    Context* ctx = GET_CONTEXT(env, this);
    if (!ctx)
    {
        return;
    }
    ANativeWindow* new_native_window = ANativeWindow_fromSurface(env, surface);

    if (ctx->native_window)
    {
        ANativeWindow_release(ctx->native_window);
        if (ctx->native_window == new_native_window)
        {
            GST_DEBUG("New native window is the same as the previous one at %p", ctx->native_window);
            if (ctx->pipeline)
            {
                gst_video_overlay_expose(GST_VIDEO_OVERLAY(ctx->pipeline));
                gst_video_overlay_expose(GST_VIDEO_OVERLAY(ctx->pipeline));
            }
            return;
        }
        else
        {
            GST_DEBUG("Released previous native window at %p", ctx->native_window);
            ctx->initialized = FALSE;
        }
    }

    ctx->native_window = new_native_window;
    check_initialization(ctx);
}

static void gst_surface_finalize(JNIEnv* env, jobject this)
{
    Context* ctx = GET_CONTEXT(env, this);
    if (!ctx)
    {
        return;
    }

    GST_DEBUG("Releasing native window at %p", ctx->native_window);
    if (ctx->pipeline)
    {
        gst_video_overlay_set_window_handle(GST_VIDEO_OVERLAY(ctx->pipeline), (guintptr)NULL);
        gst_element_set_state(ctx->pipeline, GST_STATE_READY);
    }

    ANativeWindow_release(ctx->native_window);
    ctx->native_window = NULL;
    ctx->initialized = FALSE;
}

static jboolean gst_controller_init(JNIEnv *env, jobject this) {
    jclass cls = (*env)->GetObjectClass(env, this);
    g_context_field_id = (*env)->GetFieldID(env, cls, GST_CONTEXT_FIELD_NAME, "J");
    Context* ctx = calloc(1, sizeof(Context));
    SET_CONTEXT(env, this, ctx);

    ctx->on_gst_initialized_id = (*env)->GetMethodID(env, cls, "onGstInitialized", "()V");
    ctx->on_gst_stream_info_id = (*env)->GetMethodID(env, cls, "onGstStreamInfo", "(Ljava/lang/String;)V");

    if (!g_context_field_id || !ctx->on_gst_initialized_id)
    {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "The calling class does not implement necessary methods and fields.");
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

jint JNI_OnLoad(JavaVM* vm, void* reserved)
{
    UNUSED(reserved);
    JNINativeMethod native_methods[] = {
        {"gstInit", "()V",                              gst_controller_start},
        {"gstFinalize", "()V",                          gst_finalize},
        {"gstSetMediaUri", "(Ljava/lang/String;)V",     gst_media_set_uri},
        {"gstPlay", "()V",                              gst_media_play},
        {"gstPause", "()V",                             gst_media_pause},
        {"gstSurfaceInit", "(Landroid/view/Surface;)V", gst_surface_init},
        {"gstSurfaceFinalize", "()V",                   gst_surface_finalize},
        {"gstClassInit", "()Z",                         gst_controller_init}
    };

    JNIEnv* env = NULL;
    jvm = vm;

    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_4) != JNI_OK)
    {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Could not retrieve JNIEnv!");
        return 0;
    }

    jclass class = (*env)->FindClass(env, "com/nabto/edge/tunnelvideodemo/VideoController");
    (*env)->RegisterNatives(env, class, native_methods, ARRAY_SIZE(native_methods));
    pthread_key_create(&current_jni_env, detach_current_thread);
    return JNI_VERSION_1_4;
}
