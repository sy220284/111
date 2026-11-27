/* 111-owned adapter. External CLI bytes remain precompiled, immutable APK native libraries. */
#define _POSIX_C_SOURCE 200809L
#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

struct command { const char *alias, *binary, *group, *mode; };
#include "developer_tool_commands.h"

static void fail(const char *what) { perror(what); exit(126); }
static void join(char *out, size_t n, const char *a, const char *b) {
    int result = snprintf(out, n, "%s/%s", a, b);
    if (result < 0 || (size_t)result >= n) { errno=ENAMETOOLONG; fail("runtime path"); }
}
int main(int argc, char **argv) {
    if (argc < 1 || !argv[0]) { errno=EINVAL; fail("command alias"); }
    const char *name = strrchr(argv[0], '/');
    name = name ? name + 1 : argv[0];
    const struct command *cmd = NULL;
    for (size_t i=0; i<sizeof(commands)/sizeof(commands[0]); ++i)
        if (!strcmp(name, commands[i].alias)) { cmd=&commands[i]; break; }
    if (!cmd) { errno=ENOENT; fail("unknown tool alias"); }
    const char *root = getenv("CODEX_DEVELOPER_RUNTIME_ROOT");
    if (!root || root[0] != '/') { errno=EINVAL; fail("CODEX_DEVELOPER_RUNTIME_ROOT"); }
    char native[PATH_MAX], binary[PATH_MAX], group[PATH_MAX], libs[PATH_MAX], path[PATH_MAX*2+2];
    ssize_t length=readlink("/proc/self/exe",native,sizeof(native)-1);
    if (length<0 || (size_t)length>=sizeof(native)-1) fail("native executable path");
    native[length]=0;
    char *slash=strrchr(native,'/');
    if (!slash) { errno=EINVAL; fail("native executable path"); }
    *slash=0;
    join(binary,sizeof(binary),native,cmd->binary);
    join(group,sizeof(group),root,cmd->group);
    join(libs,sizeof(libs),group,"lib");
    int n=snprintf(path,sizeof(path),"%s:%s",libs,native);
    if (n<0 || (size_t)n>=sizeof(path)) { errno=ENAMETOOLONG; fail("library path"); }
    if (setenv("LD_LIBRARY_PATH",path,1)) fail("LD_LIBRARY_PATH");
    char extra[PATH_MAX], pythonhome[PATH_MAX], openssl[PATH_MAX];
    if (!strcmp(cmd->group,"python")) {
        join(pythonhome,sizeof(pythonhome),group,"home");
        join(openssl,sizeof(openssl),pythonhome,"etc/tls/openssl.cnf");
        if (setenv("PYTHONHOME",pythonhome,1) || setenv("OPENSSL_CONF",openssl,1)) fail("Python environment");
    }
    if (!strcmp(cmd->mode,"direct")) {
        execv(binary,argv);
        fail(binary);
    }
    char **args=calloc((size_t)argc+3,sizeof(char*));
    if (!args) fail("argv allocation");
    args[0]=(char*)binary;
    size_t offset;
    if (!strcmp(cmd->mode,"npm")) {
        join(extra,sizeof(extra),group,"npm/package/bin/npm-cli.js");
        args[1]=extra; offset=1;
    } else if (!strcmp(cmd->mode,"pip")) {
        args[1]="-m"; args[2]="pip"; offset=2;
    } else { errno=EINVAL; fail("command mode"); }
    for (int i=1;i<argc;++i) args[(size_t)i+offset]=argv[i];
    execv(binary,args);
    fail(binary);
    return 126;
}
