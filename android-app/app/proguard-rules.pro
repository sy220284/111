# App is not using class-name reflection for its RPC methods.
# Preserve JNI signatures if a new native callback bridge is introduced.
-keepclasseswithmembernames class * {
    native <methods>;
}
# Native Codex/Git/ripgrep executables are not processed by R8.
