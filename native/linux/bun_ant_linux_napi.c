/* Native Linux implementation of the private Bun.ant operations used by the
 * restored 2.1.310 Linux code. Uses OS syscalls, never guessed return values.
 * This is an independently written compatibility layer, NOT the original Bun source.
 */
#define _GNU_SOURCE
#include <node_api.h>
#include <errno.h>
#include <limits.h>
#include <stdbool.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <termios.h>
#include <unistd.h>

static void system_error(napi_env env,const char *where,int err){
    char message[256];snprintf(message,sizeof(message),"%s: %s (errno=%d)",where,strerror(err),err);
    napi_throw_error(env,NULL,message);
}
static bool fd_argument(napi_env env,napi_callback_info info,int *fd){
    napi_value argv[1]; size_t argc=1;
    if(napi_get_cb_info(env,info,&argc,argv,NULL,NULL)!=napi_ok)return false;
    int32_t n=0;
    if(argc!=1||napi_get_value_int32(env,argv[0],&n)!=napi_ok){
        napi_throw_type_error(env,NULL,"Expected one integer file descriptor");return false;
    }
    *fd=n;return true;
}
static napi_value native_get_peer_uid(napi_env env,napi_callback_info info){
    int fd;if(!fd_argument(env,info,&fd))return NULL;
    struct ucred cred; socklen_t size=sizeof(cred);
    if(getsockopt(fd,SOL_SOCKET,SO_PEERCRED,&cred,&size)<0){system_error(env,"SO_PEERCRED",errno);return NULL;}
    napi_value result;napi_create_uint32(env,cred.uid,&result);return result;
}
static napi_value native_get_peer_pid(napi_env env,napi_callback_info info){
    int fd;if(!fd_argument(env,info,&fd))return NULL;
    struct ucred cred;socklen_t size=sizeof(cred);
    if(getsockopt(fd,SOL_SOCKET,SO_PEERCRED,&cred,&size)<0){system_error(env,"SO_PEERCRED",errno);return NULL;}
    napi_value result;napi_create_int32(env,cred.pid,&result);return result;
}
static napi_value native_tty_name(napi_env env,napi_callback_info info){
    int fd;if(!fd_argument(env,info,&fd))return NULL;
    char name[PATH_MAX];int err=ttyname_r(fd,name,sizeof(name));
    napi_value result;
    if(err==ENOTTY||err==EBADF||err==ENODEV||err==ENOENT){napi_get_null(env,&result);return result;}
    if(err!=0){system_error(env,"ttyname_r",err);return NULL;}
    napi_create_string_utf8(env,name,NAPI_AUTO_LENGTH,&result);return result;
}
static napi_value native_set_dumpable(napi_env env,napi_callback_info info){
    size_t argc=1;napi_value argv[1];bool enabled;
    if(napi_get_cb_info(env,info,&argc,argv,NULL,NULL)!=napi_ok)return NULL;
    if(argc!=1||napi_get_value_bool(env,argv[0],&enabled)!=napi_ok){
        napi_throw_type_error(env,NULL,"Expected one boolean flag");return NULL;
    }
    if(prctl(PR_SET_DUMPABLE,enabled?1:0,0,0,0)<0){system_error(env,"PR_SET_DUMPABLE",errno);return NULL;}
    napi_value result;napi_get_boolean(env,true,&result);return result;
}
static napi_value native_get_dumpable(napi_env env,napi_callback_info info){
    (void)info;int value=prctl(PR_GET_DUMPABLE,0,0,0,0);
    if(value<0){system_error(env,"PR_GET_DUMPABLE",errno);return NULL;}
    napi_value result;napi_create_int32(env,value,&result);return result;
}
/* Called only when the process has a tty but no controlling terminal, exactly
 * as in the recovered JS branch. setsid + TIOCSCTTY changes process state. */
static napi_value native_login_tty(napi_env env,napi_callback_info info){
    int fd;if(!fd_argument(env,info,&fd))return NULL;
    if(!isatty(fd)){napi_value r;napi_get_boolean(env,false,&r);return r;}
    if(setsid()<0 && errno!=EPERM){system_error(env,"setsid",errno);return NULL;}
    if(ioctl(fd,TIOCSCTTY,0)<0){napi_value r;napi_get_boolean(env,false,&r);return r;}
    if(dup2(fd,STDIN_FILENO)<0||dup2(fd,STDOUT_FILENO)<0||dup2(fd,STDERR_FILENO)<0){
        system_error(env,"dup2",errno);return NULL;
    }
    if(fd>STDERR_FILENO)close(fd);
    napi_value r;napi_get_boolean(env,true,&r);return r;
}
NAPI_MODULE_INIT(){
    napi_property_descriptor methods[]={
        {"getPeerUid",NULL,native_get_peer_uid,NULL,NULL,NULL,napi_default,NULL},
        {"getPeerPid",NULL,native_get_peer_pid,NULL,NULL,NULL,napi_default,NULL},
        {"ttyName",NULL,native_tty_name,NULL,NULL,NULL,napi_default,NULL},
        {"setDumpable",NULL,native_set_dumpable,NULL,NULL,NULL,napi_default,NULL},
        {"getDumpable",NULL,native_get_dumpable,NULL,NULL,NULL,napi_default,NULL},
        {"loginTty",NULL,native_login_tty,NULL,NULL,NULL,napi_default,NULL}
    };
    napi_define_properties(env,exports,sizeof(methods)/sizeof(methods[0]),methods);
    return exports;
}