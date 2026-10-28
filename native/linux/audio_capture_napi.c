/* Independently implemented Linux N-API audio backend using the native ALSA ABI.
 * The original proprietary Rust implementation is not present in source form.
 * Native capture/playback paths are operational when an ALSA device is configured;
 * original timing/error semantics and platform-specific behavior are not fully restored.
 */
#define _POSIX_C_SOURCE 200809L
#include <node_api.h>
#include <pthread.h>
#include <stdint.h>
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

/* ALSA declarations without -dev headers (runtime libasound.so.2 is available). */
typedef struct _snd_pcm snd_pcm_t;
extern int snd_pcm_open(snd_pcm_t **pcm, const char *name, int stream, int mode);
extern int snd_pcm_set_params(snd_pcm_t *pcm, int format, int access, unsigned int channels,unsigned int rate,int soft_resample,unsigned int latency);
extern long snd_pcm_readi(snd_pcm_t *pcm,void *buffer,unsigned long size);
extern long snd_pcm_writei(snd_pcm_t *pcm,const void *buffer,unsigned long size);
extern int snd_pcm_prepare(snd_pcm_t *pcm);
extern int snd_pcm_drop(snd_pcm_t *pcm);
extern int snd_pcm_close(snd_pcm_t *pcm);

static pthread_mutex_t audio_mu=PTHREAD_MUTEX_INITIALIZER;
static snd_pcm_t *capture=NULL,*playback=NULL;
static bool recording=false,playing=false;
static pthread_t capture_thread;
static napi_threadsafe_function capture_tsfn=NULL;
static napi_ref stop_callback_ref=NULL;
static unsigned playback_channels=1;

static napi_value undef(napi_env env){napi_value out;napi_get_undefined(env,&out);return out;}
static napi_value nbool(napi_env env,bool value){napi_value out;napi_get_boolean(env,value,&out);return out;}
static void fail(napi_env env,const char*msg){napi_throw_error(env,NULL,msg);}
static void free_audio(void*data){free(data);}
static void deliver_capture(napi_env env,napi_value js_callback,void*ctx,void*data){
    (void)ctx;
    if(!env||!data){free(data);return;}
    uint8_t *p=data;
    uint32_t length;memcpy(&length,p,4);
    napi_value arr,global;
    if(napi_create_array_with_length(env,length,&arr)==napi_ok&&napi_get_undefined(env,&global)==napi_ok){
        for(uint32_t i=0;i<length;i++){
            napi_value n;
            if(napi_create_uint32(env,(uint32_t)p[4+i],&n)!=napi_ok||napi_set_element(env,arr,i,n)!=napi_ok)break;
        }
        napi_value ignored;napi_call_function(env,global,js_callback,1,&arr,&ignored);
    }
    free_audio(data);
}
static void *record_loop(void*ignored){
    (void)ignored;
    while(true){
        pthread_mutex_lock(&audio_mu);bool active=recording;snd_pcm_t*handle=capture;pthread_mutex_unlock(&audio_mu);
        if(!active||!handle)break;
        uint8_t buf[3200]; long frames=snd_pcm_readi(handle,buf,1600);
        if(frames<0){if(snd_pcm_prepare(handle)<0)break;continue;}
        uint32_t n=(uint32_t)frames*2u;
        uint8_t*mem=malloc(4+n);if(!mem)break;
        memcpy(mem,&n,4);memcpy(mem+4,buf,n);
        if(napi_call_threadsafe_function(capture_tsfn,mem,napi_tsfn_nonblocking)!=napi_ok)free(mem);
    }
    pthread_mutex_lock(&audio_mu);
    snd_pcm_t*to_close=capture;capture=NULL;recording=false;
    pthread_mutex_unlock(&audio_mu);
    if(to_close)snd_pcm_close(to_close);
    if(capture_tsfn){napi_release_threadsafe_function(capture_tsfn,napi_tsfn_release);capture_tsfn=NULL;}
    return NULL;
}
static napi_value start_recording(napi_env env,napi_callback_info info){
    size_t argc=2;napi_value a[2];napi_get_cb_info(env,info,&argc,a,NULL,NULL);
    napi_valuetype t=napi_undefined;
    if(argc==0||napi_typeof(env,a[0],&t)!=napi_ok||t!=napi_function){fail(env,"startRecording requires audio-data callback");return NULL;}
    pthread_mutex_lock(&audio_mu);
    if(recording){pthread_mutex_unlock(&audio_mu);return nbool(env,true);}
    snd_pcm_t*pcm=NULL;int rc=snd_pcm_open(&pcm,"default",1,0);
    if(rc<0||!pcm){pthread_mutex_unlock(&audio_mu);return nbool(env,false);}
    rc=snd_pcm_set_params(pcm,2,3,1,16000,1,100000);
    if(rc<0){snd_pcm_close(pcm);pthread_mutex_unlock(&audio_mu);return nbool(env,false);}
    napi_value resource;napi_create_string_utf8(env,"claude-audio-capture",NAPI_AUTO_LENGTH,&resource);
    if(napi_create_threadsafe_function(env,a[0],NULL,resource,16,1,NULL,NULL,NULL,deliver_capture,&capture_tsfn)!=napi_ok){
        snd_pcm_close(pcm);pthread_mutex_unlock(&audio_mu);return nbool(env,false);
    }
    if(argc>=2){napi_valuetype done_type=napi_undefined;
        if(napi_typeof(env,a[1],&done_type)==napi_ok&&done_type==napi_function)
            napi_create_reference(env,a[1],1,&stop_callback_ref);
    }
    capture=pcm;recording=true;
    if(pthread_create(&capture_thread,NULL,record_loop,NULL)!=0){recording=false;capture=NULL;snd_pcm_close(pcm);napi_release_threadsafe_function(capture_tsfn,napi_tsfn_abort);capture_tsfn=NULL;pthread_mutex_unlock(&audio_mu);return nbool(env,false);}
    pthread_detach(capture_thread);pthread_mutex_unlock(&audio_mu);
    return nbool(env,true);
}
static napi_value stop_recording(napi_env env,napi_callback_info info){
    (void)info;pthread_mutex_lock(&audio_mu);bool was_recording=recording;recording=false;
    if(capture)snd_pcm_drop(capture);
    napi_ref done_ref=stop_callback_ref;
    stop_callback_ref=NULL;
    pthread_mutex_unlock(&audio_mu);
    if(was_recording&&done_ref){
        napi_value done,receiver,ignored;
        if(napi_get_reference_value(env,done_ref,&done)==napi_ok&&napi_get_undefined(env,&receiver)==napi_ok)
            napi_call_function(env,receiver,done,0,NULL,&ignored);
        napi_delete_reference(env,done_ref);
    }
    return undef(env);
}
static napi_value is_recording(napi_env env,napi_callback_info info){
    (void)info;pthread_mutex_lock(&audio_mu);bool b=recording;pthread_mutex_unlock(&audio_mu);return nbool(env,b);
}
static napi_value start_playback(napi_env env,napi_callback_info info){
    size_t argc=2;napi_value a[2];napi_get_cb_info(env,info,&argc,a,NULL,NULL);
    uint32_t rate=0,channels=0;
    if(argc<1||napi_get_value_uint32(env,a[0],&rate)!=napi_ok){fail(env,"Failed to convert napi value Undefined into rust type `u32`");return NULL;}
    if(argc<2||napi_get_value_uint32(env,a[1],&channels)!=napi_ok){
        napi_valuetype t=napi_undefined;if(argc>=2)napi_typeof(env,a[1],&t);
        fail(env,t==napi_function?"Failed to convert napi value Function into rust type `u32`":"Failed to convert napi value Undefined into rust type `u32`");return NULL;
    }
    if(!rate||!channels||channels>8)return nbool(env,false);
    pthread_mutex_lock(&audio_mu);
    if(playback){snd_pcm_close(playback);playback=NULL;playing=false;}
    snd_pcm_t*pcm=NULL;int rc=snd_pcm_open(&pcm,"default",0,0);
    if(rc>=0&&pcm)rc=snd_pcm_set_params(pcm,2,3,channels,rate,1,100000);
    if(rc<0||!pcm){if(pcm)snd_pcm_close(pcm);pthread_mutex_unlock(&audio_mu);return nbool(env,false);}
    playback=pcm;playback_channels=channels;playing=true;pthread_mutex_unlock(&audio_mu);
    return nbool(env,true);
}
static napi_value stop_playback(napi_env env,napi_callback_info info){
    (void)info;pthread_mutex_lock(&audio_mu);
    if(playback){snd_pcm_drop(playback);snd_pcm_close(playback);playback=NULL;}
    playing=false;pthread_mutex_unlock(&audio_mu);return undef(env);
}
static napi_value is_playing(napi_env env,napi_callback_info info){
    (void)info;pthread_mutex_lock(&audio_mu);bool b=playing;pthread_mutex_unlock(&audio_mu);return nbool(env,b);
}
static napi_value write_playback(napi_env env,napi_callback_info info){
    size_t argc=1;napi_value args[1];napi_get_cb_info(env,info,&argc,args,NULL,NULL);
    bool isbuffer=false,isarray=false;void*bytes=NULL;size_t len=0;
    if(argc){napi_is_buffer(env,args[0],&isbuffer);
        if(isbuffer)napi_get_buffer_info(env,args[0],&bytes,&len);
        else{napi_is_typedarray(env,args[0],&isarray);if(isarray){napi_typedarray_type t;size_t elements;size_t off;napi_value b;
            napi_get_typedarray_info(env,args[0],&t,&elements,&bytes,&b,&off);
            len=elements*(t==napi_float32_array||t==napi_int32_array||t==napi_uint32_array?4:(t==napi_int16_array||t==napi_uint16_array?2:1));
        }}
    }
    pthread_mutex_lock(&audio_mu);
    if(playing&&playback&&bytes&&len>=2u*playback_channels){
        long rc=snd_pcm_writei(playback,bytes,len/(2u*playback_channels));
        if(rc<0){snd_pcm_prepare(playback);}
    }
    pthread_mutex_unlock(&audio_mu);return undef(env);
}
static napi_value microphone_status(napi_env env,napi_callback_info info){
    (void)info;napi_value result;napi_create_int32(env,3,&result);return result;
}
NAPI_MODULE_INIT(){
    napi_property_descriptor p[]={
        {"isPlaying",0,is_playing,0,0,0,napi_enumerable,0},
        {"isRecording",0,is_recording,0,0,0,napi_enumerable,0},
        {"stopPlayback",0,stop_playback,0,0,0,napi_enumerable,0},
        {"startPlayback",0,start_playback,0,0,0,napi_enumerable,0},
        {"stopRecording",0,stop_recording,0,0,0,napi_enumerable,0},
        {"startRecording",0,start_recording,0,0,0,napi_enumerable,0},
        {"writePlaybackData",0,write_playback,0,0,0,napi_enumerable,0},
        {"microphoneAuthorizationStatus",0,microphone_status,0,0,0,napi_enumerable,0}
    };
    napi_define_properties(env,exports,8,p);return exports;
}