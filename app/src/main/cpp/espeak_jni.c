#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <espeak-ng/speak_lib.h>
#include <android/log.h>

#define LOG_TAG "EspeakJni"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static short *audio_buffer = NULL;
static int audio_buffer_size = 0;
static int audio_buffer_capacity = 0;

int synth_callback(short *wav, int num_samples, espeak_EVENT *events) {
    if (wav == NULL) return 0;

    if (audio_buffer_size + num_samples > audio_buffer_capacity) {
        int new_capacity = (audio_buffer_capacity + num_samples) * 2;
        short *new_buffer = realloc(audio_buffer, new_capacity * sizeof(short));
        if (new_buffer == NULL) return 1; // Error
        audio_buffer = new_buffer;
        audio_buffer_capacity = new_capacity;
    }

    memcpy(audio_buffer + audio_buffer_size, wav, num_samples * sizeof(short));
    audio_buffer_size += num_samples;

    return 0;
}

JNIEXPORT jint JNICALL
Java_com_nikosm_voiceassistant_EspeakEngine_nativeInit(JNIEnv *env, jobject thiz, jstring data_path) {
    const char *path = (*env)->GetStringUTFChars(env, data_path, NULL);
    int sample_rate = espeak_Initialize(AUDIO_OUTPUT_SYNCHRONOUS, 0, path, 0);
    (*env)->ReleaseStringUTFChars(env, data_path, path);

    espeak_SetSynthCallback(synth_callback);
    return sample_rate;
}

JNIEXPORT jint JNICALL
Java_com_nikosm_voiceassistant_EspeakEngine_nativeSetVoice(JNIEnv *env, jobject thiz, jstring voice_name) {
    const char *name = (*env)->GetStringUTFChars(env, voice_name, NULL);
    int result = espeak_SetVoiceByName(name);
    (*env)->ReleaseStringUTFChars(env, voice_name, name);
    return result;
}

JNIEXPORT jshortArray JNICALL
Java_com_nikosm_voiceassistant_EspeakEngine_nativeSynthesize(JNIEnv *env, jobject thiz, jstring text) {
    const char *c_text = (*env)->GetStringUTFChars(env, text, NULL);

    audio_buffer_size = 0;
    if (audio_buffer == NULL) {
        audio_buffer_capacity = 44100; // 1 second at 44.1kHz
        audio_buffer = malloc(audio_buffer_capacity * sizeof(short));
    }

    unsigned int flags = espeakCHARS_AUTO | espeakPHONEMES | espeakENDPAUSE;
    espeak_Synth(c_text, strlen(c_text) + 1, 0, POS_CHARACTER, 0, flags, NULL, NULL);
    espeak_Synchronize();

    (*env)->ReleaseStringUTFChars(env, text, c_text);

    jshortArray result = (*env)->NewShortArray(env, audio_buffer_size);
    (*env)->SetShortArrayRegion(env, result, 0, audio_buffer_size, audio_buffer);

    return result;
}

JNIEXPORT void JNICALL
Java_com_nikosm_voiceassistant_EspeakEngine_nativeTerminate(JNIEnv *env, jobject thiz) {
    espeak_Terminate();
    if (audio_buffer != NULL) {
        free(audio_buffer);
        audio_buffer = NULL;
        audio_buffer_capacity = 0;
        audio_buffer_size = 0;
    }
}
