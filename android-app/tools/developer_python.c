#include <Python.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

int main(int argc, char **argv) {
    const char *name = strrchr(argv[0], '/');
    name = name ? name + 1 : argv[0];
    if (!strcmp(name, "pip") || !strcmp(name, "pip3")) {
        const char *python = getenv("CODEX_PYTHON_EXECUTABLE");
        if (!python || !*python) { fputs("Python environment is not initialized\n", stderr); return 126; }
        char **args = calloc((size_t)argc + 3, sizeof(char *));
        if (!args) return 1;
        args[0] = (char *)python; args[1] = "-m"; args[2] = "pip";
        for (int i = 1; i < argc; ++i) args[i + 2] = argv[i];
        int result = Py_BytesMain(argc + 2, args);
        free(args);
        return result;
    }
    return Py_BytesMain(argc, argv);
}
