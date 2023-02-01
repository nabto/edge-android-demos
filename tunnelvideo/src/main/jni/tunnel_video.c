#include <string.h>
#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <gst/gst.h>
#include <gst/video/video.h>

GST_DEBUG_CATEGORY_STATIC(debug_category);
#define GST_CAT_DEFAULT debug_category

#define SEEK_MIN (500 * GST_MSECOND)

#if GLIB_SIZEOF_VOID_P == 8
#define GET_CUSTOM_DATA(env, this, field_id) (Context*)(*(env))->GetLongField((env), (this), (field_id))
#define SET_CUSTOM_DATA(env, this, field_id, data) (*(env))->SetLongField((env), (this), (field_id), (jlong)(data))
#else
#define GET_CUSTOM_DATA(env, this, field_id) (Context*)(*(env))->GetLongField((env), (this), (field_id))
#define SET_CUSTOM_DATA(env, this, field_id, data) (*(env))->SetLongField((env), (this), (field_id), (jlong)(jint)(data))
#endif

typedef struct Context
{
    jobject app;
    GstElement* pipeline;
    GMainContext* context;
    GMainLoop* main_loop;
    gboolean initialized;
    ANativeWindow* native_window;

    GstState state;
    GstState target_state;

    GstClockTime last_seek_time;
    gboolean is_live;
} Context;

typedef enum {
    GST_PLAY_FLAG_VIDEO = (1 << 0),
    GST_PLAY_FLAG_AUDIO = (1 << 1),
    GST_PLAY_FLAG_TEXT = (1 << 2)
} GstPlayFlags;

static const char* TAG = "TunnelVideoNative";
static JavaVM* jvm;
static pthread_t gst_app_thread;
static pthread_key_t current_jni_env;
static jfieldID custom_data_field_id;
static jmethodID on_gstreamer_initialized_method_id;

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

static void seek(Context* ctx, gint64 desired_position)
{
    if (desired_position == GST_CLOCK_TIME_NONE)
    {
        return;
    }

    gint64 diff = (gint64)(gst_util_get_timestamp() - ctx->last_seek_time);
    if (!(GST_CLOCK_TIME_IS_VALID(ctx->last_seek_time) && diff < SEEK_MIN))
    {
        GST_DEBUG("Seeking to %" GST_TIME_FORMAT, GST_TIME_ARGS(desired_position));
        ctx->last_seek_time = gst_util_get_timestamp();
        gst_element_seek_simple(ctx->pipeline, GST_FORMAT_TIME, GST_SEEK_FLAG_FLUSH | GST_SEEK_FLAG_KEY_UNIT, desired_position);
    }
}

static void error_callback(GstBus* bus, GstMessage* msg, Context* ctx)
{
    GError* err;
    gchar* debug_info;
    gchar* msg_string;

    gst_message_parse_error(msg, &err, &debug_info);
    msg_string = g_strdup_printf("Error received from element %s: %s", GST_OBJECT_NAME(msg->src), err->message);
    g_clear_error(&err);
    g_free(debug_info);
    g_free(msg_string);
    gst_element_set_state(ctx->pipeline, GST_STATE_NULL);
}

static void end_of_stream_callback(GstBus* bus, GstMessage* msg, Context* ctx)
{
    ctx->target_state = GST_STATE_PAUSED;
    ctx->is_live = (gst_element_set_state(ctx->pipeline, GST_STATE_PAUSED) == GST_STATE_CHANGE_NO_PREROLL);
    seek(ctx, 0);
}

static void clock_lost_callback(GstBus* bus, GstMessage* msg, Context* ctx)
{
    if (ctx->target_state >= GST_STATE_PLAYING)
    {
        gst_element_set_state(ctx->pipeline, GST_STATE_PAUSED);
        gst_element_set_state(ctx->pipeline, GST_STATE_PLAYING);
    }
}


static void state_changed_callback(GstBus* bus, GstMessage* msg, Context* ctx)
{
    GstState old_state, new_state, pending_state;
    gst_message_parse_state_changed(msg, &old_state, &new_state, &pending_state);

    // Messages may come from children of the pipeline.
    // Ensure we only check messages from the actual pipeline.
    if (GST_MESSAGE_SRC(msg) == GST_OBJECT(ctx->pipeline))
    {
        ctx->state = new_state;
        gchar* message = g_strdup_printf("State changed to %s", gst_element_state_get_name(new_state));
        // @TODO: Log this
        g_free(message);

        if (old_state == GST_STATE_READY && new_state == GST_STATE_PAUSED)
        {
            // @TODO
            // The sink at this point knows the media resolution
            // We could communicate it to the Java code from here.
        }
    }
}

static void check_initialization(Context* ctx)
{
    JNIEnv* env = get_jni_env();
    if (!ctx->initialized && ctx->native_window && ctx->main_loop)
    {
        GST_DEBUG("Initialization complete, notifying application. native_window::%p main_loop::%p", ctx->native_window, ctx->main_loop);

        gst_video_overlay_set_window_handle(GST_VIDEO_OVERLAY(ctx->pipeline), (guintptr)ctx->native_window);
        (*env)->CallVoidMethod(env, ctx->app, on_gstreamer_initialized_method_id);
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
    JavaVMAttachArgs args;
    GstBus* bus;
    Context* ctx = (Context*)userdata;
    GSource* bus_source;
    GError* error = NULL;

    GST_DEBUG("Creating pipeline in Context at %p", ctx);

    ctx->context = g_main_context_new();
    g_main_context_push_thread_default(ctx->context);

    //ctx->pipeline = gst_parse_launch("videotestsrc ! clockoverlay ! videoconvert ! autovideosink", &error);
    ctx->pipeline = gst_parse_launch("playbin uridecodebin0::source::latency=0", &error);
    if (error)
    {
        // @TODO: Log the error->message
        g_clear_error(&error);
        return NULL;
    }

    ctx->target_state = GST_STATE_READY;
    gst_element_set_state(ctx->pipeline, ctx->target_state);

    // get the pipeline's bus to forward messages from the streaming threads to our callbacks.
    bus = gst_element_get_bus(ctx->pipeline);
    bus_source = gst_bus_create_watch(bus);
    g_source_set_callback(bus_source, (GSourceFunc) gst_bus_async_signal_func, NULL, NULL);
    g_source_attach(bus_source, ctx->context);
    g_source_unref(bus_source);
    g_signal_connect(G_OBJECT(bus), "message::error", (GCallback)error_callback, ctx);
    g_signal_connect(G_OBJECT(bus), "message::state-changed", (GCallback)state_changed_callback, ctx);
    g_signal_connect(G_OBJECT(bus), "message::eos", (GCallback)end_of_stream_callback, ctx);
    g_signal_connect(G_OBJECT(bus), "message::clock-lost", (GCallback)clock_lost_callback, ctx);
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

static void gst_nabto_init(JNIEnv* env, jobject this)
{
    Context* ctx = g_new0(Context, 1);

    SET_CUSTOM_DATA(env, this, custom_data_field_id, ctx);
    GST_DEBUG_CATEGORY_INIT(debug_category, TAG, 0, "Nabto Tunnel Video Native");

    gst_debug_set_threshold_for_name(TAG, GST_LEVEL_DEBUG);
    GST_DEBUG("Created context at %p", ctx);

    ctx->app = (*env)->NewGlobalRef(env, this);
    GST_DEBUG("Created GlobalRef for app object at %p", ctx->app);

    ctx->last_seek_time = GST_CLOCK_TIME_NONE;

    pthread_create(&gst_app_thread, NULL, &app_main, ctx);
}

static void gst_nabto_set_uri(JNIEnv* env, jobject this, jstring uri)
{
    Context* ctx = GET_CUSTOM_DATA(env, this, custom_data_field_id);
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

static void gst_nabto_finalize(JNIEnv* env, jobject this)
{
    Context* ctx = GET_CUSTOM_DATA(env, this, custom_data_field_id);
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
    SET_CUSTOM_DATA(env, this, custom_data_field_id, NULL);
    GST_DEBUG("Done finalizing.");
}

static void gst_nabto_play(JNIEnv* env, jobject this)
{
    Context* ctx = GET_CUSTOM_DATA(env, this, custom_data_field_id);
    if (!ctx)
    {
        return;
    }

    GST_DEBUG("Setting state to PLAYING.");
    ctx->target_state = GST_STATE_PLAYING;
    ctx->is_live = (gst_element_set_state(ctx->pipeline, ctx->target_state) == GST_STATE_CHANGE_NO_PREROLL);
}

static void gst_nabto_pause(JNIEnv* env, jobject this)
{
    Context* ctx = GET_CUSTOM_DATA(env, this, custom_data_field_id);
    if (!ctx)
    {
        return;
    }

    GST_DEBUG("Setting state to PAUSED");
    ctx->target_state = GST_STATE_PAUSED;
    ctx->is_live = (gst_element_set_state(ctx->pipeline, ctx->target_state) == GST_STATE_CHANGE_NO_PREROLL);
}

static void gst_nabto_surface_init(JNIEnv* env, jobject this, jobject surface)
{
    Context* ctx = GET_CUSTOM_DATA(env, this, custom_data_field_id);
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

static void gst_nabto_surface_finalize(JNIEnv* env, jobject this)
{
    Context* ctx = GET_CUSTOM_DATA(env, this, custom_data_field_id);
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

static jboolean gst_nabto_class_init(JNIEnv *env, jobject thiz) {
    // @TODO: Maybe there is a better way than looking up methods and fields by string?
    jclass cls = (*env)->GetObjectClass(env, thiz);
    custom_data_field_id = (*env)->GetFieldID(env, cls, "native_custom_data", "J");
    on_gstreamer_initialized_method_id = (*env)->GetMethodID(env, cls, "onGStreamerInitialized", "()V");
    if (!custom_data_field_id || !on_gstreamer_initialized_method_id)
    {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "The calling class does not implement necessary methods and fields.");
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

jint JNI_OnLoad(JavaVM* vm, void* reserved)
{
    static JNINativeMethod native_methods[] = {
            {"nativeInit", "()V", gst_nabto_init},
            {"nativeFinalize", "()V", gst_nabto_finalize},
            {"nativeSetUri", "(Ljava/lang/String;)V", gst_nabto_set_uri},
            {"nativePlay", "()V", gst_nabto_play},
            {"nativePause", "()V", gst_nabto_pause},
            {"nativeSurfaceInit", "(Landroid/view/Surface;)V", gst_nabto_surface_init},
            {"nativeSurfaceFinalize", "()V", gst_nabto_surface_finalize},
            {"nativeClassInit", "()Z", gst_nabto_class_init}
    };

    JNIEnv* env = NULL;
    jvm = vm;

    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_4) != JNI_OK)
    {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Could not retrieve JNIEnv!");
        return 0;
    }

    jclass class = (*env)->FindClass(env, "com/nabto/edge/tunnelvideodemo/VideoController");
    (*env)->RegisterNatives(env, class, native_methods, G_N_ELEMENTS(native_methods));
    pthread_key_create(&current_jni_env, detach_current_thread);
    return JNI_VERSION_1_4;
}
