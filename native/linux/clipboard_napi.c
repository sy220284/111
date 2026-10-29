/* Linux clipboard N-API replacement. X11 async read/write path; Wayland intentionally not implemented.
 * Independently authored from observed N-API exports and public X11 APIs.
 * NOT a decompilation of the original Rust source and not claimed behavior-identical.
 */
#define _POSIX_C_SOURCE 200809L
#include <node_api.h>
#include <X11/Xlib.h>
#include <X11/Xatom.h>
#include <pthread.h>
#include <stdint.h>
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <time.h>
#include <unistd.h>

static pthread_mutex_t mu=PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t cv=PTHREAD_COND_INITIALIZER;
static pthread_t owner_thread;
static bool owner_started=false, owner_ready=false, owner_alive=false, owner_update=false, owner_ack=false;
static char *owner_text=NULL;
static size_t owner_length=0;
static Display *owner_display=NULL;
static Window owner_window=0;
static Atom atom_clipboard=0, atom_utf8=0, atom_targets=0, atom_text=0;

static void n_error(napi_env env, const char*msg){napi_throw_error(env,NULL,msg);}
static napi_value n_boolean(napi_env env,bool b){napi_value r;napi_get_boolean(env,b,&r);return r;}
static int get_string(napi_env env,napi_value v,char**str,size_t *len){
    size_t n=0; if(napi_get_value_string_utf8(env,v,NULL,0,&n)!=napi_ok)return 0;
    char*p=malloc(n+1);if(!p)return 0;
    if(napi_get_value_string_utf8(env,v,p,n+1,&n)!=napi_ok){free(p);return 0;}
    *str=p;*len=n;return 1;
}
static void *owner_loop(void*unused){
    (void)unused; XInitThreads(); Display *d=XOpenDisplay(NULL);
    pthread_mutex_lock(&mu);
    owner_display=d; owner_alive=(d!=NULL);
    if(d){owner_window=XCreateSimpleWindow(d,DefaultRootWindow(d),0,0,1,1,0,0,0);
        atom_clipboard=XInternAtom(d,"CLIPBOARD",False);
        atom_utf8=XInternAtom(d,"UTF8_STRING",False);
        atom_targets=XInternAtom(d,"TARGETS",False);
        atom_text=XInternAtom(d,"TEXT",False);}
    owner_ready=true;pthread_cond_broadcast(&cv);pthread_mutex_unlock(&mu);
    if(!d)return NULL;
    while(1){
        pthread_mutex_lock(&mu);
        if(owner_update){
            XSetSelectionOwner(d,atom_clipboard,owner_window,CurrentTime);
            XSetSelectionOwner(d,XA_PRIMARY,owner_window,CurrentTime);
            XSync(d,False);
            owner_ack=(XGetSelectionOwner(d,atom_clipboard)==owner_window);
            owner_update=false;pthread_cond_broadcast(&cv);
        }
        while(XPending(d)>0){
            XEvent event;XNextEvent(d,&event);
            if(event.type==SelectionRequest){
                XSelectionRequestEvent*r=&event.xselectionrequest;
                XEvent reply;memset(&reply,0,sizeof(reply));
                reply.xselection.type=SelectionNotify;
                reply.xselection.display=d;
                reply.xselection.requestor=r->requestor;
                reply.xselection.selection=r->selection;
                reply.xselection.target=r->target;
                reply.xselection.time=r->time;
                reply.xselection.property=None;
                Atom prop=r->property==None?r->target:r->property;
                if(r->target==atom_targets){
                    Atom types[]={atom_targets,atom_utf8,XA_STRING,atom_text};
                    XChangeProperty(d,r->requestor,prop,XA_ATOM,32,PropModeReplace,(unsigned char*)types,4);
                    reply.xselection.property=prop;
                }else if(r->target==atom_utf8||r->target==XA_STRING||r->target==atom_text){
                    XChangeProperty(d,r->requestor,prop,r->target,8,PropModeReplace,(unsigned char*)(owner_text?owner_text:""),(int)owner_length);
                    reply.xselection.property=prop;
                }
                XSendEvent(d,r->requestor,False,0,&reply);XFlush(d);
            }
        }
        pthread_mutex_unlock(&mu);
        struct timespec interval={0,3000000};nanosleep(&interval,NULL);
    }
    return NULL;
}
static bool make_owner(void){
    pthread_mutex_lock(&mu);
    if(!owner_started){owner_started=true;owner_ready=false;
        if(pthread_create(&owner_thread,NULL,owner_loop,NULL)!=0){owner_ready=true;owner_alive=false;}
        else pthread_detach(owner_thread);
    }
    while(!owner_ready)pthread_cond_wait(&cv,&mu);
    bool ok=owner_alive;pthread_mutex_unlock(&mu);return ok;
}
static napi_value set_text(napi_env env,napi_callback_info info){
    size_t argc=1;napi_value argv[1];
    if(napi_get_cb_info(env,info,&argc,argv,NULL,NULL)!=napi_ok)return NULL;
    char*p=NULL;size_t len=0;
    if(argc<1||!get_string(env,argv[0],&p,&len)){
        napi_valuetype t=napi_undefined;
        if(argc>0)napi_typeof(env,argv[0],&t);
        if(t==napi_number){double number=0;napi_get_value_double(env,argv[0],&number);
            char msg[180];snprintf(msg,sizeof(msg),"Failed to convert JavaScript value `Number %g ` into rust type `String`",number);
            n_error(env,msg);
        }else n_error(env,"Failed to convert JavaScript value into rust type `String`");
        return NULL;
    }
    if(!make_owner()){free(p);return n_boolean(env,false);}
    pthread_mutex_lock(&mu);free(owner_text);owner_text=p;owner_length=len;
    owner_ack=false;owner_update=true;
    pthread_cond_broadcast(&cv);
    while(owner_update)pthread_cond_wait(&cv,&mu);
    bool success=owner_ack;pthread_mutex_unlock(&mu);
    return n_boolean(env,success);
}
static long long monotonic_ms(void){struct timespec t;clock_gettime(CLOCK_MONOTONIC,&t);return t.tv_sec*1000LL+t.tv_nsec/1000000LL;}
static char *read_x11(bool primary,uint32_t timeout,uint32_t *out_len,const char **err){
    XInitThreads();Display*d=XOpenDisplay(NULL);
    if(!d){*err="no reachable display server: Unknown error while interacting with the clipboard: X11 server connection timed out because it was unreachable";return NULL;}
    Window w=XCreateSimpleWindow(d,DefaultRootWindow(d),0,0,1,1,0,0,0);
    Atom selection=primary?XA_PRIMARY:XInternAtom(d,"CLIPBOARD",False);
    Atom utf8=XInternAtom(d,"UTF8_STRING",False);
    Atom property=XInternAtom(d,"NAPI_CLIPBOARD_RESULT",False);
    if(XGetSelectionOwner(d,selection)==None){*err="The clipboard contents were not available in the requested format or the clipboard is empty.";XDestroyWindow(d,w);XCloseDisplay(d);return NULL;}
    XConvertSelection(d,selection,utf8,property,w,CurrentTime);XFlush(d);
    long long deadline=monotonic_ms()+(timeout?timeout:1);
    char *res=NULL;unsigned long nitems=0;bool notified=false;
    while(monotonic_ms()<=deadline){
        if(XPending(d)>0){XEvent e;XNextEvent(d,&e);if(e.type==SelectionNotify&&e.xselection.selection==selection){
            notified=true;
            if(e.xselection.property!=None){Atom type;int format;unsigned long after=0;unsigned char*data=NULL;
                if(XGetWindowProperty(d,w,property,0,262144,True,AnyPropertyType,&type,&format,&nitems,&after,&data)==Success&&format==8&&data){
                    res=malloc(nitems+1);if(res){memcpy(res,data,nitems);res[nitems]=0;*out_len=(uint32_t)nitems;}}
                if(data)XFree(data);
            }
            break;
        }}
        struct timespec ts={0,2000000};nanosleep(&ts,NULL);
    }
    XDestroyWindow(d,w);XCloseDisplay(d);
    if(!res)*err=notified?"The clipboard contents were not available in the requested format or the clipboard is empty.":"no answer before the deadline";
    return res;
}
/* The original Rust addon uses napi_create_async_work for reads.  Keep all X11
 * waits off the JavaScript event loop and settle the Promise on its thread. */
typedef struct {
    bool primary;
    uint32_t timeout;
    uint32_t length;
    char *text;
    const char *error;
    napi_deferred deferred;
    napi_async_work work;
} clipboard_read_job;

static void execute_read(napi_env env, void *opaque){
    (void)env;
    clipboard_read_job *job=(clipboard_read_job *)opaque;
    job->text=read_x11(job->primary,job->timeout,&job->length,&job->error);
}
static void complete_read(napi_env env,napi_status status,void *opaque){
    clipboard_read_job *job=(clipboard_read_job *)opaque;
    if(status==napi_ok && job->text){
        napi_value text;
        if(napi_create_string_utf8(env,job->text,job->length,&text)==napi_ok){
            napi_resolve_deferred(env,job->deferred,text);
        }else{
            napi_value err,message;
            napi_create_string_utf8(env,"Failed to decode clipboard text",NAPI_AUTO_LENGTH,&message);
            napi_create_error(env,NULL,message,&err);
            napi_reject_deferred(env,job->deferred,err);
        }
    }else{
        napi_value message,err;
        napi_create_string_utf8(env,status==napi_ok && job->error ? job->error : "Clipboard read cancelled",NAPI_AUTO_LENGTH,&message);
        napi_create_error(env,NULL,message,&err);
        napi_reject_deferred(env,job->deferred,err);
    }
    napi_delete_async_work(env,job->work);
    free(job->text);
    free(job);
}
static napi_value get_text(napi_env env,napi_callback_info info){
    size_t argc=2;napi_value args[2];
    if(napi_get_cb_info(env,info,&argc,args,NULL,NULL)!=napi_ok)return NULL;
    bool primary=false;uint32_t timeout=0;
    if(argc<1||napi_get_value_bool(env,args[0],&primary)!=napi_ok){n_error(env,"Failed to convert napi value into rust type `bool`");return NULL;}
    if(argc<2||napi_get_value_uint32(env,args[1],&timeout)!=napi_ok){
        napi_valuetype t=napi_undefined;
        if(argc>=2)napi_typeof(env,args[1],&t);
        n_error(env,t==napi_string?"Failed to convert napi value String into rust type `u32`":"Failed to convert napi value Undefined into rust type `u32`");return NULL;
    }
    clipboard_read_job *job=calloc(1,sizeof(*job));
    if(!job){n_error(env,"Unable to allocate clipboard read job");return NULL;}
    job->primary=primary;job->timeout=timeout;
    napi_value promise,resource;
    if(napi_create_promise(env,&job->deferred,&promise)!=napi_ok){free(job);return NULL;}
    napi_create_string_utf8(env,"claude-clipboard-read",NAPI_AUTO_LENGTH,&resource);
    if(napi_create_async_work(env,NULL,resource,execute_read,complete_read,job,&job->work)!=napi_ok){
        free(job);n_error(env,"Unable to create clipboard async work");return NULL;
    }
    if(napi_queue_async_work(env,job->work)!=napi_ok){
        napi_delete_async_work(env,job->work);free(job);
        n_error(env,"Unable to queue clipboard async work");return NULL;
    }
    return promise;
}
NAPI_MODULE_INIT(){
    napi_property_descriptor props[]={
        {"getLinuxClipboardText",0,get_text,0,0,0,napi_enumerable,0},
        {"setLinuxClipboardText",0,set_text,0,0,0,napi_enumerable,0}
    };
    napi_define_properties(env,exports,2,props);
    return exports;
}