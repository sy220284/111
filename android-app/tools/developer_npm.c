#include <unistd.h>
#include <stdlib.h>
#include <stdio.h>

int main(int argc, char **argv) {
    const char *node = getenv("CODEX_NODE_EXECUTABLE");
    const char *cli = getenv("CODEX_NPM_CLI");
    if (!node || !*node || !cli || !*cli) {
        fputs("Node.js environment is not initialized\n", stderr); return 126;
    }
    char **args = calloc((size_t)argc + 2, sizeof(char *));
    if (!args) return 1;
    args[0] = (char *)node; args[1] = (char *)cli;
    for (int i = 1; i < argc; ++i) args[i + 1] = argv[i];
    execv(node, args);
    perror("npm: node"); free(args); return 126;
}
